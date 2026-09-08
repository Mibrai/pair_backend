# Réponse — `GET /recaps/mine` ne coûte plus rien par carte

**Date :** 2026-09-08 · Réponse à [`PROMPT_BACKEND_2026-09-08.md`](PROMPT_BACKEND_2026-09-08.md)

> **C'est corrigé, et la cause n'était pas là où on la cherche d'habitude.**
>
> Votre relevé était juste sur toute la ligne, y compris sur les trois suspects
> que vous nommiez « sans prétendre nommer la cause » : les trois étaient
> coupables. Le rendu d'une carte coûtait **huit requêtes SQL**, soit ~288 pour
> vos 35 cartes.
>
> **Mais ce n'était pas cher en calcul, c'était cher en distance.** La base est à
> San Francisco, le service en Europe : environ **200 ms l'aller-retour**. C'est
> le seul chiffre qui manquait à votre analyse, et il explique tout — vos 1,7 s
> par carte, ce sont huit à neuf allers-retours transatlantiques. §1.
>
> **Après :** 12 requêtes pour 1 carte, 12 pour 10, **12 pour 35**. Le coût
> marginal par carte est **nul**. §2.
>
> **Ni pagination, ni délai relevé** : vous aviez raison sur les deux, et pour
> les raisons que vous donniez. §3.
>
> **5a — pas de cache**, et il n'en faut plus : le calcul n'est plus cher. §4.1.
> **5b — `/users/{id}/recaps` est corrigé par le même geste**, comme vous
> l'aviez prévu. §4.2.
>
> ⚠️ **Votre § 6 est périmé.** B4 a été répondu, B5 est livré, et l'écart sur
> `AfficheDto` est celui que nous avions nous-mêmes soulevé — le tout dans
> [`REPONSE_BACKEND_2026-09-07.md`](REPONSE_BACKEND_2026-09-07.md), écrite hier
> soir. Vous ne l'aviez visiblement pas reçue. Les trois réponses sont reprises
> au § 5 pour que vous n'ayez pas à la chercher.

---

## 1. La cause, et pourquoi vos chiffres la désignaient déjà

### 1.1 Ce que nous avons mesuré avant de toucher quoi que ce soit

Nous n'avons pas commencé par lire le code en cherchant ce qui « avait l'air
lent ». Nous avons écrit un harnais qui **compte les requêtes SQL réellement
émises** par `getMine`, et les regroupe par texte —
`RecapsMineQueryCountIntegrationTest`.

Une durée mesurée chez nous n'aurait rien voulu dire : nos tests tournent sur un
Postgres local, où l'aller-retour vaut 0,1 ms. Le **nombre** de requêtes, lui,
est le même que chez vous. C'est la seule grandeur qui se transporte.

| | requêtes SQL |
|---|---:|
| 1 carte | 16 |
| 10 cartes | 93 |
| **coût marginal par carte** | **8** |
| projection à 35 cartes | **~288** |

### 1.2 Les huit requêtes, et vos trois intuitions

```
 3 ×  le profil de l'hôte   count(subscriptions), subscriptions(abonné,auteur), badge_awards⋈badges
 1 ×  topVibes              recap_vibe_votes GROUP BY vibe
 1 ×  myVibes               recap_vibe_votes WHERE recap_id AND user_id
 1 ×  publicPhotos          attendances WHERE schedule AND attended_at
 1 ×  visibleAttendees      recap_participant_consents
 1 ×  nextSlot              schedules … prochain créneau ouvert
```

Vos trois hypothèses, dans l'ordre où vous les posiez :

1. **« Si ce bloc est résolu par carte plutôt que par hôte, il est payé 35 fois
   pour 3 réponses distinctes. »** Exact. Trois requêtes par carte, pour trois
   hôtes.
2. **« `nextSlot` … soit une recherche du prochain créneau **et** un décompte de
   participants, par carte. »** Exact, et pire que ce que vous pensiez : le
   décompte de participants en coûtait **deux** de plus par créneau trouvé.
3. **« `visibleAttendees` n'est non vide que sur 1 carte sur 35, ce qui coûte 34
   requêtes pour rien. »** Exact, au mot près.

Une seule de vos intuitions était trop pessimiste, et elle mérite d'être
corrigée parce qu'elle vous servira ailleurs : le **chargement de l'hôte
lui-même** ne coûtait rien. Le contexte de persistance mémorise déjà l'entité par
identifiant à l'intérieur d'une transaction. Ce sont les trois **agrégats
attachés** au profil — abonnés, abonnement, badges — qui ne passaient pas par
lui.

### 1.3 Le chiffre que vous ne pouviez pas voir

**La base de données est à San Francisco ; le service tourne en Europe.** Chaque
aller-retour JDBC coûte donc ~200 ms, quelle que soit la requête. C'est un
plancher connu de ce dépôt, et c'est lui qui transforme un défaut ordinaire en
panne.

Le modèle prédit `288 × 0,2 s ≈ 58 s`. Vous mesurez **60 à 63 s**.

Et votre propre relevé le confirme une seconde fois, indépendamment :
`/users/{id}/recaps` rend **une** carte en 3,99 s, soit 3,11 s au-dessus de votre
plancher de 0,88 s — pour les 16 requêtes que nous comptons sur une carte. Cela
donne **~195 ms par requête**. Vos deux mesures, prises sur deux routes
différentes, donnent la même constante.

C'est pourquoi optimiser le SQL n'aurait presque rien donné. Il fallait
supprimer des **allers-retours**, pas les rendre plus rapides.

---

## 2. Ce que nous avons changé, et le résultat

Une seule règle, et rien ne la contourne : **aucune lecture ne se fait plus carte
par carte.**

| | avant | après |
|---|---:|---:|
| 1 carte | 16 requêtes | **12** |
| 10 cartes | 93 | **12** |
| **35 cartes** (3 hôtes, 3 programmes) | ~288 | **12** |
| coût marginal par carte | 8 | **0** |
| à ~200 ms l'aller-retour | ~58 s | **~2,4 s** |

Les 35 cartes ne sont pas extrapolées : elles sont **mesurées**, sur la forme
exacte de votre relevé. Une extrapolation linéaire aurait menti, puisque ce qui
reste ne croît plus avec les cartes.

Quatre gestes :

- **le lot est chargé d'un coup** — ambiances, vos ambiances, consentements,
  présences : une requête chacun pour les 35 cartes, au lieu d'une par carte ;
- **les profils se résolvent par personne**, plus par carte. Trois hôtes pour
  trente-cinq cartes, ce sont trois profils. Le chemin unitaire et le chemin
  groupé finissent dans **la même fabrique privée** : la règle de visibilité
  d'un profil n'est pas recopiée, seule la façon de rassembler ses entrées
  change. Une seconde définition aurait servi des profils plus bavards sur une
  page que sur une autre, sans qu'aucune erreur ne le dise ;
- **`nextSlot` se demande par programme**, et « est-ce que j'y ai déjà ma
  place ? » se pose en deux requêtes pour tout le lot, contre deux **par
  créneau** ;
- **l'arbre créneau → programme → activité → catégorie** arrive avec les cartes,
  au lieu de se déplier paresseusement en six requêtes.

**Le rendu d'une carte seule passe par le même chemin**, avec un lot d'un
élément. Un second chemin de rendu aurait fini par diverger du premier — et
c'est le rendu qui porte les règles de confidentialité.

### 2.1 Ce qui empêchera le défaut de revenir

Le harnais reste dans le dépôt, et son assertion est devenue un garde-fou :
**trente-cinq cartes ne doivent pas coûter plus que dix.** Ce n'est pas une durée
qui est verrouillée — elle dépend d'une machine — mais le fait que le coût ne
suive plus le nombre de cartes.

Le jour où quelqu'un rouvre un N+1 dans ce rendu, c'est cette ligne qui le dit.
Avant la production, qui mettait une minute à le dire.

---

## 3. Ce que nous n'avons pas fait, et pourquoi vous aviez raison

**Pas de pagination.** Votre argument est le bon et il n'avait pas besoin d'être
défendu : une affiche est une **transition**, pas un état. Dire « première fois
en escalade » suppose de connaître tout ce qui précède ; sur une page de dix
cartes, l'app annoncerait une première fois qui n'en est pas une. Et le client
aurait payé les mêmes soixante secondes, en plusieurs fois.

**Pas de « montez votre délai ».** Vous l'écriviez : cela aurait échangé une
absence contre une minute d'écran figé. Déplacer un défaut n'est pas le corriger.

Nous notons au passage que votre `receiveTimeout` de 30 s **n'avait jamais été
atteint par aucune autre route**. C'est un bon capteur : gardez-le tel quel.

---

## 4. Vos deux questions annexes

### 4.1 Une mise en cache est-elle prévue ?

**Non, et il n'en faut plus.** Vous proposiez une réponse mémorisée par
utilisateur, invalidée sur contribution. C'était la bonne idée tant que le calcul
restait cher ; il ne l'est plus. Douze requêtes, c'est le coût d'une lecture
ordinaire de ce dépôt.

Ajouter un cache maintenant coûterait ce que coûtent tous les caches — une
invalidation à tenir juste — pour gagner deux secondes sur un écran qui en met
trois. Et une invalidation ratée sur cette route-ci se paierait en affiches qui
ne se déclenchent pas, ce que votre module ne peut pas se permettre. Nous
préférons vous le proposer plus tard, si vos mesures le réclament, plutôt que de
l'installer par précaution.

### 4.2 `/users/{id}/recaps` aussi

**Corrigé par le même geste**, comme vous l'aviez prévu. Les quatre routes de
lecture — `/recaps/mine`, `/users/{id}/recaps`, `/programs/{id}/recaps`,
`/activities/{id}/recaps` — et le fil géolocalisé passent toutes par le même
rendu. Aucune n'a de code propre à corriger, et aucune ne peut diverger des
autres demain.

C'est la route qui dessinera l'affiche d'autrui le jour où la publication
s'allumera : elle est prête.

---

## 5. Votre § 6 : les trois points sont déjà répondus

Ils l'étaient hier soir, dans
[`REPONSE_BACKEND_2026-09-07.md`](REPONSE_BACKEND_2026-09-07.md). Les voici en
bref, pour que rien ne dépende de la réception de ce document.

### 5.1 B4 — que rend `/recaps/mine` ?

**Seulement les cartes portant au moins une contribution.** Une séance où vous
étiez présent mais où personne n'a voté d'ambiance, écrit un mot ou partagé une
photo n'apparaît nulle part. La carte naît de la **première contribution**,
jamais d'avance.

Vous notiez que votre relevé ne permettait pas de trancher, puisque vos 35 cartes
en portent toutes une. C'est exact, et c'est cohérent : les autres n'existent
pas.

**Votre monotonie est donc nécessaire.** Et le défaut a une **seconde cause** que
votre document ne mentionne pas : une **présence confirmée tardivement**. La
confirmation se fait après la séance, sur relance, et rien n'oblige à la faire le
soir même. Quelqu'un qui confirme lundi une séance de la semaine précédente fait
entrer d'un coup une carte ancienne — déjà contributive — dans son histoire. Une
monotonie qui ne protégerait que du premier cas laisserait passer celui-là.

Si vous voulez retrouver la propriété « zéro état », nous pouvons livrer un
`GET /api/attendances/mine` rendant **une entrée par présence confirmée**
(`scheduleId`, `slotStartedAt`, `activityId`, `activityName`,
`categoryColorRamp`) — l'histoire sur laquelle vos transitions se calculent
réellement, au lieu de la lire à travers des cartes qui n'en sont qu'un reflet
partiel. Une demi-journée. Elle n'existe pas aujourd'hui.

### 5.2 B5 — `AFFICHE_READY`

**Livré.** Le type existe, il est émis à la naissance de la carte-souvenir — soit
l'instant exact où l'affiche devient calculable — et **au plus une fois par
séance et par personne**, garanti par le schéma et non par un compteur.

`data.type` **part réellement** : la clé est posée par le point d'envoi, à partir
du type, et non par les producteurs. Elle ne figure pas dans les clés évincables
sous la limite APNs de 4 Ko, et `scheduleId` non plus.

Il est absent de votre relevé parce qu'il n'est pas encore **déployé** : il est
dans `master` depuis hier soir.

⚠️ **Un point à confirmer.** Vous écrivez « aucun enrichissement
`programTitle`/`placeName`/`sessionAt` ». Nous ne savons pas si cela décrit ce
que **vous** ne faites pas, ou ce que vous nous demandez de **ne pas envoyer**.
En l'état, la charge porte : `type`, `scheduleId`, `slotStartedAt`, `sessionAt`,
`programTitle`, `activityName`, `placeName`, la teinte de catégorie et l'auteur
du programme. `sessionAt` et `slotStartedAt` portent la **séance vécue**, jamais
le début que la ligne de créneau affiche aujourd'hui — sur une série récurrente,
le rollover l'a déjà avancée d'une semaine.

Si vous préférez une charge réduite à `type` + `scheduleId`, dites-le : c'est
trois lignes. Nous ne l'avons pas fait, parce qu'un `title` et un `body` réels —
que vous demandez par ailleurs — se composent mieux avec le titre du programme
qu'avec « Nouvelle notification ».

### 5.3 L'écart sur `AfficheDto`

C'est **nous** qui l'avions soulevé, au § 6 de la réponse d'hier, et le constat
est le même que le vôtre : avec le contrat que vous aviez spécifié, un lecteur
reçoit `scheduleId` et `motif`, et la carte-souvenir qui porte le titre,
l'activité et la date lui est refusée en `404` tant que l'hôte ne l'a pas
publiée — c'est-à-dire dans le cas majoritaire.

Trois issues étaient proposées, et le choix vous appartient parce qu'il porte sur
ce que « publier une affiche » veut dire. Nous penchons pour la deuxième :
ajouter à `AfficheDto` le strict nécessaire — `activityName`,
`categoryColorRamp`, et rien d'autre. Une demi-journée, et une décision produit
que nous ne prenons pas seuls.

**À trancher avant d'allumer `affichePublication`**, vous avez raison.

---

## 6. Vérification

**1 222 tests, aucun échec** : 507 unitaires, 715 d'intégration. Le périmètre
touché dépassait largement les cartes-souvenirs — le rendu d'un profil public
sert cinq surfaces, l'audience d'un créneau en sert deux autres — et c'est
précisément pourquoi la suite entière a été passée plutôt que les seules classes
du lot.

Deux rouges sont apparus en chemin, tous deux **instruits jusqu'à leur cause**
plutôt qu'excusés : une configuration de test en cours de modification par un
autre chantier, et un conteneur Postgres injoignable sous charge. Rejoués en
isolation : verts, et sans rapport avec ce lot.
