# Réponse backend aux correctifs de performance — 22 août 2026

> Réponse à `CORRECTIFS-BACKEND.md`. Votre diagnostic est juste et nous l'avons
> suivi : les §1 à §5 sont livrés, le §6 l'est partiellement et délibérément.
> Le §0, le §7, le §8 et le §9 ne le sont pas — trois d'entre eux sortent du
> code, le quatrième attend un arbitrage. Ce document dit aussi où votre rapport
> se trompe, sur quatre points dont deux changeaient le travail à faire.
>
> **Nous n'avons mesuré aucun temps de réponse.** Tout ce qui suit est un compte
> de requêtes SQL. La preuve reste à faire, et c'est votre campagne qui la fera.

---

## Ce que votre diagnostic a vu juste

Le raisonnement central est le bon, et il nous a fait gagner l'essentiel du
temps d'instruction : le temps suit le **nombre d'éléments rendus**, pas la
surface fouillée. Votre observation qu'au-delà de 8 km le temps cesse net
d'augmenter alors que la zone est multipliée par 14 est décisive — un défaut
d'index spatial aurait produit l'inverse.

Nous l'avons vérifiée dans le schéma plutôt que de vous croire sur parole :
l'index GiST existe bien (`V5__create_programs_schedules_media.sql:40`,
`CREATE INDEX idx_schedules_location ON schedules USING GIST(location)`). Le
bornage géographique n'est pas en cause. C'était bien le mapping.

---

## Quatre rectifications à votre document

Nous les listons parce que vos documents servent de référence d'un lot à
l'autre, et que deux d'entre elles changeaient le travail à faire.

### 1. `findOpenSlotIdsInRadius` n'existait pas

Votre §1 l'invoque comme s'il suffisait de l'appeler. La méthode qui existe est
`findLocatedScheduleIdsWithin`, et elle ne porte **ni** la fenêtre temporelle,
**ni** les filtres langue / étiquettes / activité, **ni** le prédicat de blocage,
**ni** le classement par disponibilité. L'appeler aurait rendu un fil aux
mauvais filtres et sans tri.

Il a donc fallu créer la variante — voir ci-dessous, où nous expliquons pourquoi
nous ne l'avons pas écrite en recopiant la requête existante.

Accessoirement, le commentaire que vous citez n'est pas à `SlotService:53-60`
(c'est le champ `zoneId`) mais à `ScheduleRepository:53-60`.

### 2. `getPrivacySettings()` ne coûte aucune requête

Votre §2 le compte comme une association paresseuse. C'est un `@Embedded`
(`User.java:96-98`) : les colonnes sont dans la table `users`, elles arrivent avec
l'utilisateur. Le coût d'un profil public était de **5** requêtes, pas 6, plus
une par badge.

Sans importance pour le correctif, mais votre estimation « 12-20 requêtes par
élément » repose sur ce compte.

### 3. Votre §5 sous-estimait `/programs`, et le patron du §1 n'y suffisait pas

`ProgramService.toDto` exécutait par programme : les séances (puis un DTO par
séance), les médias, la moyenne des avis, le nombre d'avis, le nombre
d'inscrits, **plus** un `p.getSchedules()` qui rechargeait la collection
paresseuse alors que la ligne précédente venait de lire les mêmes lignes par le
dépôt. Six requêtes, plus les chaînes paresseuses, le tout multiplié par
`LIMIT 100`.

Des `JOIN FETCH` ne règlent que les chaînes. Les agrégats demandaient un travail
distinct — c'est là qu'était le gros du gain.

### 4. Votre §8 n'est pas une politique à assouplir, c'est un compteur cassé

Développé plus bas, mais le point mérite d'être ici : `RateLimiter.checkRegister`
n'a **aucune fenêtre glissante**. Le compteur s'accumule pour la durée de vie du
composant. « Réessayez dans 1 heure » est faux — c'est jusqu'au prochain
redémarrage du service.

Vos « ~2 inscriptions/heure/IP » ne sont donc pas un réglage sévère : c'est un
budget de 5 inscriptions qui n'a jamais été reconstitué depuis le dernier
déploiement. Une exemption d'IP aurait masqué le défaut au lieu de le corriger.

---

## §1 — `GET /slots/feed` : ids, puis rechargement avec les fetch joins

Livré comme vous le décriviez, avec une différence de méthode.

Nous n'avons **pas** recopié la requête native pour en faire une variante `ids`.
Le corps — jointures, filtres, prédicat de blocage, classement — est extrait dans
une constante partagée par les deux formes :

```java
String OPEN_SLOTS_IN_RADIUS_BODY = """ FROM schedules s … ORDER BY … LIMIT :limit """;

@Query(value = "SELECT s.* " + OPEN_SLOTS_IN_RADIUS_BODY, nativeQuery = true)
List<Schedule> findOpenSlotsInRadius(…);

@Query(value = "SELECT s.id " + OPEN_SLOTS_IN_RADIUS_BODY, nativeQuery = true)
List<UUID> findOpenSlotIdsInRadius(…);
```

Soixante lignes de SQL en double auraient fini par diverger, et un filtre ajouté
d'un seul côté aurait rendu un fil incohérent avec sa propre variante. C'est
l'argument que `BlockSql` porte déjà dans ce dépôt pour le seul prédicat de
blocage ; il vaut a fortiori pour un corps de cette taille.

`getSlotFeed` fait désormais : ids → `findWithActivityDetailsByIds` →
**réordonnancement selon la liste d'ids**. Votre avertissement sur l'ordre était
le bon et nous l'avons écrit en javadoc sur les deux méthodes : la perte du tri
est muette, la page garderait les mêmes créneaux, seulement mélangés, et aucun
test ne l'aurait vue.

`findOpenSlotsInRadius` reste en place : `SemanticSearchService` et
`SlotCancellationService` l'appellent.

**Effet :** quatre requêtes par créneau supprimées (program, userActivity,
activity, category).

## §2 et §3 — profils et participations, calculés une fois par lot

Un `FeedContext` porte les deux tables — profils publics indexés par hôte,
participations indexées par créneau — construites une fois pour l'ensemble du
lot. `toFeedItem` y lit au lieu d'appeler les services.

Deux précisions sur la mise en œuvre :

**Le cas d'un créneau seul passe par le même chemin**, exprimé comme un lot d'un
élément. Un second chemin aurait pu diverger du premier, et c'est le genre
d'écart qui se paie en fuite de données quand il porte sur la visibilité d'un
lieu.

**`SlotAddressVisibility` reçoit une surcharge** qui prend la participation déjà
chargée. Le prédicat y est passé **paresseux** : un lieu `PUBLIC`, ou dont
l'adresse exacte est assumée, se tranche sans rien demander à personne, et c'est
le cas courant. L'évaluer d'avance aurait rétabli la requête qu'on venait de
supprimer.

Nuance à votre §3 : ce n'était pas deux requêtes systématiques par créneau mais
une à deux — `resolve` court-circuitait déjà avant la requête pour les lieux
publics et en ligne.

`getMySlots` bénéficie du même traitement.

## §4 — badges

`findByUserIdWithBadge` avec `JOIN FETCH a.badge`, et le chargement **déplacé
derrière le test de visibilité** : sur un profil masqué (privé, ou `FRIENDS` sans
abonnement), les badges étaient chargés puis jetés, puisque le DTO rend
`List.of()`. Le coût y tombe désormais à zéro.

`findByUserId` est conservée — `BadgeService` l'appelle encore, et il souffre
probablement du même défaut ; c'est un candidat pour un lot suivant.

Vous aviez raison de l'appeler le multiplicateur le plus vicieux : il croissait
avec le nombre de badges gagnés, donc il empirait à mesure que vos utilisateurs
devenaient actifs.

## §5 — `GET /programs`

- Le doublon `p.getSchedules()` supprimé.
- Patron ids + fetch joins, avec l'ordre repris depuis la liste d'ids.
- **Agrégats groupés** : une requête pour les séances de toute la page, une pour
  les médias, une pour le couple moyenne + nombre d'avis (un seul `GROUP BY` au
  lieu de deux requêtes), une pour les inscrits.

Une page de 100 programmes passe d'environ **600 à 1000 requêtes** à **6, quel
que soit le nombre de programmes**.

La requête native qui rendait des entités s'est retrouvée sans appelant ; nous
l'avons supprimée plutôt que de laisser le même SQL exister en deux exemplaires
dont un mort.

## §6 — configuration : deux réglages sur trois, et pourquoi pas le troisième

Posés :

```properties
spring.jpa.properties.hibernate.default_batch_fetch_size=32
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=10000
```

Votre remarque sur le pool était juste et nous l'avons reprise telle quelle dans
le fichier : onze requêtes en parallèle à chaque reprise, pour un pool de dix,
c'est un seul utilisateur qui épuise le pool. Nous avons ramené le délai
d'attente à dix secondes plutôt que trente : au-delà, votre client a de toute
façon abandonné, et une connexion encore attendue ne retient plus qu'un thread.

**`spring.jpa.open-in-view=false` n'est pas posé, et ce n'est pas un oubli.**
Vous le rangez parmi les « trois lignes, aucun risque ». Il ne l'est pas : la
bascule fait lever `LazyInitializationException` sur chaque association lue hors
transaction, et les mappers de ce projet en lisent beaucoup. Nous l'avons
documenté dans le fichier de configuration pour qu'il ne soit pas posé
distraitement. Il mérite son propre lot, avec sa propre vérification.

Vous avez raison sur le fond — c'est bien ce qui fera tomber le service en
premier sous charge. Raison de plus pour ne pas le basculer sans le vérifier.

---

## Un défaut que votre rapport n'avait pas vu

`Schedule.accessibilityTags` est une `@ElementCollection(fetch = LAZY)`
(`Schedule.java:142-148`), lue à la dernière ligne de `toFeedItem` : **une requête de
plus par créneau**, soit un sixième multiplicateur absent de votre liste.

Un `JOIN FETCH` ne pouvait pas le couvrir en même temps que le reste — deux
collections ne se ramènent pas par fetch join dans la même requête. C'est
`default_batch_fetch_size` qui s'en charge, ce qui donne au §6 une justification
plus précise que le « filet de sécurité général » de votre document.

---

## Ce qui reste proportionnel — à lire avant de fixer votre cible

Votre §7 fixe comme critère un nombre de requêtes **constant**, indépendant du
nombre d'éléments. Nous n'y sommes pas tout à fait, et nous préférons le dire
que vous le laisser le découvrir sur vos mesures.

Le fil coûte désormais, pour N créneaux et **H hôtes distincts** :

```
2 (ids + rechargement) + 1 (participations) + 4 × H + N/32 (étiquettes)
```

Le terme `4 × H` est le profil public : `findActiveUser`, le compte
d'abonnés, l'état d'abonnement, les badges. Il ne dépend plus du nombre de
créneaux — deux séances du même hôte ne coûtent qu'un profil, ce qui est le gain
de votre §2 — mais il reste proportionnel au nombre d'hôtes **distincts**.

Sur un fil de vingt créneaux tenus par quinze hôtes différents, cela fait
environ 64 requêtes contre 260 auparavant. Réel, mais pas constant.

**Ce qu'il faudrait pour l'être :** `SubscriptionService` expose déjà les
variantes par lot — `countAuthorSubscribers(Collection<UUID>)` et
`subscribedAuthorIds(UUID, Collection<UUID>)` — et `UserService` les utilise
déjà pour sa liste paginée d'utilisateurs. Faire passer le fil par ce chemin
ramènerait `4 × H` à environ 3. Nous ne l'avons pas fait dans ce lot : cela
touche le rendu des profils publics, donc les réglages de confidentialité, et
cela méritait de ne pas être empilé sur le reste. Dites-nous si vos mesures le
rendent nécessaire.

---

## Un changement de comportement à connaître

`ProgramDto.nextSession` était calculé depuis `p.getSchedules()`, une collection
paresseuse **non synchronisée après un `save`**. Dans `duplicateProgram` et
`addSchedule`, elle était vide alors que la base contenait les créneaux : le DTO
rendait `nextSession = null` à tort.

Le calcul portant désormais sur la liste lue en base, c'est corrigé. Si votre
client affichait « pas de prochaine séance » juste après une duplication ou un
ajout de créneau, il affichera maintenant la bonne date. Ce n'est pas une
optimisation, c'est un changement observable — d'où cette mention.

---

## Ce que nous n'avons pas fait

### §0 — `PGHOST` : à vérifier chez vous, et cela peut valoir plus que tout ce lot

Rien dans le dépôt ne permet de trancher : `application-railway.properties` prend
l'hôte d'une variable d'environnement. La vérification reste entière, et votre
intuition est la bonne — à ~100 ms par aller-retour contre 1 à 5 ms attendus sur
un réseau privé, c'est potentiellement un facteur 10 pour une variable
d'environnement, sans une ligne de code.

Bonne nouvelle au passage, vérifiée : `server.forward-headers-strategy=framework`
est bien posé, donc l'IP client est correctement résolue derrière le proxy — vos
limiteurs ne comptent pas toutes les requêtes du monde sur une seule adresse.

### §7 — la mesure

Nous ne l'avons pas activée : elle demande l'environnement réel, et le compte de
requêtes avant correction n'a plus d'objet maintenant que la correction est
faite. Votre protocole reste le bon pour l'après, et **c'est vous qui tenez la
preuve**. Le critère à regarder est la pente, pas le temps absolu : le temps doit
cesser de suivre le nombre d'éléments — en tenant compte de la réserve sur les
hôtes distincts ci-dessus.

### §8 — les limiteurs : nous attendons un arbitrage, pas un accord

Nous n'avons rien touché à une protection de production sans validation. Voici
ce que nous proposons, et l'état exact du code.

`RateLimiter.checkRegister` incrémente un compteur en mémoire et refuse au-delà
de 5. **Rien ne le décrémente jamais.** Idem pour `checkPasswordReset`. Le login,
lui, se rétablit correctement : verrou de 15 minutes, puis compteur remis à zéro.

Il existe par ailleurs un second limiteur, `RateLimiterService`, écrit en
bucket4j avec de **vraies fenêtres glissantes** (10 connexions / 15 min,
5 inscriptions / heure). Il est annoté
`@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`, et
`redis.enabled` vaut `false` par défaut. Autrement dit : la bonne implémentation
existe et dort, la cassée tourne.

**Notre proposition :** réparer la fenêtre plutôt que vous exempter. Une
exemption d'IP laisserait le défaut en place pour vos utilisateurs réels — un
utilisateur légitime derrière une IP partagée (entreprise, université, opérateur
mobile) se voit aujourd'hui refuser l'inscription définitivement, sans que rien
ne se rouvre jamais. C'est un défaut produit avant d'être une gêne de test.

Dites-nous si vous voulez que nous le prenions, et si un profil de recette aux
seuils relâchés vous est utile en plus.

### §9 — vos points mineurs, vérifiés un par un

**Double inscription → 422.** Confirmé : `BusinessException` est mappée en
`UNPROCESSABLE_ENTITY` (`GlobalExceptionHandler.handleBusiness`, ligne 148). Ce n'est pas un choix
réfléchi pour ce cas précis, c'est le mapping par défaut de la famille
d'exceptions. Une `ConflictException` → 409 existe déjà dans le dépôt, le
changement tient en une ligne à deux endroits.

**Nous ne l'avons pas fait, parce que c'est une rupture de contrat**, pas une
correction : tout client écrit contre le comportement actuel casse. Nous
attendons votre feu vert et une fenêtre de déploiement coordonnée avec vos
versions en circulation. Nous partageons votre lecture sur le fond — 409 est la
bonne réponse.

**`GET /programs` sans `lat`/`lng` rend mes programmes.** Confirmé
(`ProgramController.getMyPrograms`, lignes 48-58). C'est délibéré et cela restera, mais vous avez
raison que ni le nom ni le contrat ne le laissent deviner. À corriger dans la
description OpenAPI, pas dans le comportement.

**`radius_km` plafonné à 100.** Confirmé (`ProgramService:289`). Le plafond est
correct ; son absence de `/v3/api-docs` ne l'est pas. Même remarque : c'est la
documentation qui manque.

Ces deux derniers points sont de la documentation de contrat. Nous les prenons
volontiers dans un lot de mise à jour OpenAPI si vous les priorisez.

---

## Vérification

- **Ligne de base capturée avant toute modification**, sur un worktree détaché à
  `HEAD` : **763 tests, 104 classes, 0 échec, 0 erreur.**
- **Après les correctifs, à l'identique : 763 tests, 0 échec, 0 erreur.**
- Compilation propre depuis un `clean`, sources de test comprises.

Un seul test a dû être ajusté : `SlotServiceTest` programmait un mock devenu
inutile, ce qui aurait fait échouer la classe pour une raison sans rapport avec
ce qu'elle vérifie (Mockito en mode strict). Il est basculé sur la méthode par
lot ; aucune assertion n'a été modifiée.

**Ce que cette vérification ne prouve pas :** aucune de ces suites ne compte les
requêtes SQL ni ne mesure un temps de réponse. Elles prouvent l'absence de
régression fonctionnelle, rien de plus. La preuve de performance vous revient.

---

## Ce que nous attendons de vous

1. **Le §0 en premier**, avant même de rejouer la campagne : `railway variables |
   grep PGHOST`. Si l'hôte est public, tout le reste se mesure sur un plancher
   faussé.
2. **Rejouer `run.sh smoke`** et nous rendre les chiffres — en particulier la
   pente : le temps par élément supplémentaire, qui était de ~1,5 s.
3. **Votre décision sur le §8** (réparer la fenêtre, ou vous exempter, ou les
   deux) et sur le **422 → 409** du §9.

Si `/slots/feed` reste au-dessus de votre cible de 500 ms après le §0, le terme
`4 × H` décrit plus haut est le suspect suivant, et nous savons déjà comment le
réduire.
