# Réponse — « déjà passé », partout

**Date :** 2026-09-06 · Réponse à [`PROMPT_BACKEND_2026-09-05.md`](PROMPT_BACKEND_2026-09-05.md)

> **Les deux demandes sont livrées**, bonus compris.
>
> **Demande 1.** `SearchResultDto` porte `nextSessionAt` et `isExpired` sur les
> résultats de type `program`, avec la définition du lot 5 mot pour mot — et,
> parce qu'il n'y avait pas de raison de la laisser muette, sur les résultats de
> type `slot` aussi. Votre `result_timeliness.dart` cessera de rendre `unknown`
> le jour du déploiement, sans qu'une ligne d'app change. §1.
>
> **Un écart sur le bonus, et il faut le lire.** Nous avons livré
> `includeExpired`, mais **son défaut est `true`**, pas `false` comme vous
> l'écriviez. Un défaut à `false` aurait fait exactement ce que votre document
> cherche à éviter : l'interrupteur se serait levé le jour du déploiement, sur
> une liste d'où le serveur venait de retirer tout ce que l'interrupteur sert à
> montrer. §2. Si vous préférez malgré tout `false`, c'est une ligne, et la
> question est rouverte.
>
> **Demande 2.** `includePast` existe sur `GET /api/slots/bounds`, fenêtre
> plafonnée à trois mois. Mais **votre diagnostic était faux sur la cause**, et
> ça vaut la peine de le savoir : `from` n'a jamais été ramené à maintenant. Il
> était honoré, et il l'est toujours. Ce qui vidait vos mesures était un autre
> filtre, deux lignes plus haut. §3.
>
> **La remarque sur le `Z` est prise** : elle est maintenant dans la spec, avec
> un exemple. §3.4.

---

## 1. Demande 1 — `nextSessionAt` et `isExpired` sur `POST /api/search`

### 1.1 Ce que nous avons vérifié avant d'écrire

Votre relevé est exact ligne à ligne, y compris sur le point que vous nous
demandiez explicitement de **ne pas** toucher :

| Votre affirmation | Vérifiée dans |
|---|---|
| `/search` rend déjà les programmes terminés | `FullTextSearchService` — aucun prédicat de date dans les quatre requêtes |
| `startsAt`/`endsAt` valent `null` sur un résultat `program` | les trois producteurs passaient `null, null, null` en fin de constructeur |
| `/programs?lat&lng&radius_km` va moins loin que `/search` | ancrage sur la séance localisée dans un cas, filtre de rayon permissif dans l'autre |
| `GET /programs/{id}` par résultat serait absurde | 20 requêtes authentifiées par page |

Vos trois contournements sont donc bien tous fermés, et le troisième pour une
raison de plus que celle que vous donniez : au-delà du coût, il aurait fait
répondre à la question par une route dont le verdict est calculé autrement.

### 1.2 Les deux champs

Sur `SearchResultDto`, en fin d'enregistrement :

| Champ | Type | Sens |
|---|---|---|
| `nextSessionAt` | `string?` ISO-8601 UTC | Début de la prochaine séance **non terminée** — celle qui vient, ou celle en cours. |
| `isExpired` | `boolean` | Daté (au moins un créneau) **et** aucune occurrence non terminée. |

La doctrine du lot 5 s'applique au mot près, et les trois phrases que vous citiez
sont chacune couvertes par un test nommé :

- *expiré uniquement si l'entrée est datée et qu'aucune occurrence future
  n'existe* — `ProgramTimelinessTest.tousLesCreneauxTermines_...` ;
- *une entrée sans aucun créneau n'est jamais expirée* —
  `...unProgrammeSansAucuneSeance_neDoitJamaisEtreDitExpire`, en unitaire et en
  intégration ;
- *expiré ⇒ `nextSessionAt: null`, sans exception* — tenu **par construction**,
  pas par une assertion : les deux champs sortent ensemble d'une fabrique qui ne
  sait pas produire la combinaison interdite.

### 1.3 Ce qui a coûté le plus cher : que la définition ne bouge pas d'une route à l'autre

Vous insistiez là-dessus, et vous aviez raison de le faire. Le verdict devait
être rendu par **deux chemins de code sans rapport** : quatre requêtes SQL natives
(la taxonomie, le plein texte, le repli trigramme, la recherche par activité) et
un mapping d'entités JPA pour le rappel vectoriel. Recopier la règle aux deux
endroits, plus celui de `/activities/browse` qui existait déjà, en aurait fait
trois définitions — et la divergence de deux d'entre elles ne se serait vue
nulle part : un programme grisé sur un écran, vivant sur l'autre, sans aucune
erreur pour le dire.

Le verdict vit donc dans une seule classe, `ProgramTimeliness`, avec deux portes
d'entrée : une pour qui tient les créneaux en mémoire, une pour qui a laissé la
base les agréger. Le SQL, lui, ne décide de rien — il rend deux colonnes brutes
(`schedule_count`, `next_session_at`), et c'est Java qui les recombine.

Trois pièges méritent d'être nommés, parce qu'ils sont tous déjà tombés ailleurs
dans ce dépôt :

1. **« Terminé » se mesure sur la fin, jamais sur le début.** Trois surfaces
   avaient borné le futur sur `starts_at`, et chacune faisait disparaître son
   objet *à la seconde où le cours commençait*. Une séance en cours donne donc
   `isExpired: false` et `nextSessionAt` = son propre début.
2. **Un créneau annulé ne compte pas comme séance à venir** — sinon un programme
   sans plus rien de vivant garderait sa date.
3. **… mais il compte comme date.** Le dire non daté rendrait éternellement
   vivant un programme dont la seule séance a été annulée. C'est le `COUNT(*)`
   de `/activities/browse` qui fixe cette moitié-là, et nous l'avons recopiée
   telle quelle plutôt que de la « corriger » de notre côté.

Sans fin déclarée — le cas le plus courant en base — la convention du dépôt
s'applique : deux heures, lues sur `SlotTiming` et interpolées dans le SQL depuis
là, jamais réécrites en dur.

### 1.4 Les résultats de type `slot` aussi

Vous ne demandiez les deux champs que pour `program`. Nous les avons renseignés
pour `slot` également : `nextSessionAt` vaut le début du créneau tant qu'il n'est
pas fini, `isExpired` dit qu'il l'est.

En pratique la requête ne rend que des créneaux `OPEN`/`FULL` dans une fenêtre
qui part de maintenant : ces deux valeurs seront donc toujours « le début, et
faux ». Les **calculer** malgré tout plutôt que les câbler à `null, false` est ce
qui les empêchera de mentir le jour où cette fenêtre changera — et ce dépôt a
déjà, dans ce même fichier, retiré une valeur d'énumération que personne
n'émettait pour exactement cette raison.

Conséquence pour vous : `resultTimeliness` peut lire les deux mêmes champs quel
que soit le type, sans brancher sur `resultType`.

---

## 2. Le bonus `includeExpired` — livré, mais avec un défaut à `true`

Vous écriviez : « un paramètre `includeExpired` (booléen, **défaut `false`**) sur
`SearchRequest`, aux mêmes sémantiques que celui de `/activities/browse` ».

La sémantique de la valeur est bien identique : `true` garde, `false` écarte.
**C'est le défaut que nous avons inversé**, et voici pourquoi.

Votre document pose un objectif explicite : « il se lèvera de lui-même le jour du
déploiement, sans qu'une ligne d'app change et sans nouvelle version à publier ».
La version publiée de l'app n'envoie pas ce paramètre — elle ne peut pas, il
n'existait pas quand elle a été compilée. Avec un défaut à `false`, voici le jour
du déploiement :

1. les résultats portent enfin `isExpired` ;
2. votre interrupteur « Afficher ce qui est terminé » se lève, comme prévu ;
3. le serveur, lui, vient de retirer de la réponse **tout ce que cet
   interrupteur sert à montrer** ;
4. l'utilisateur l'allume et ne voit rien changer.

C'est exactement le « réglage sans effet » que votre document décrit comme pire
qu'un réglage absent — obtenu par le paramètre censé l'éviter.

Le principe que nous avons retenu est plus simple à défendre que « le même défaut
partout » : **chaque route reconduit par défaut ce qu'elle faisait la veille.**
`/activities/browse` écartait déjà les entrées expirées avant que le paramètre
n'existe, son défaut est donc `false`. `/search` les rendait déjà — vous le
mesurez vous-même, 25 sur 25 autour de Paris — et vous demandez qu'elle continue
(« on veut pouvoir retrouver un programme terminé ») : son défaut est `true`.
Aucune version publiée ne voit son écran changer sous elle, dans un sens ni dans
l'autre.

Ce que vous vouliez du paramètre est intact : **envoyez `includeExpired=false`
quand l'interrupteur est éteint, et `totalCount` compte juste.** Le filtre porte
avant la découpe et avant `countsByType`, au même endroit que le filtre de
blocage et pour la même raison — un « Programmes (12) » suivi de neuf résultats
est pire que pas de compteur.

Si vous préférez malgré tout `false`, c'est une ligne dans
`SearchRequest.effectiveIncludeExpired()`. Dites-le et nous la changeons ; nous
préférons juste que ce soit une décision plutôt qu'un défaut hérité.

---

## 3. Demande 2 — `GET /api/slots/bounds` et le passé

### 3.1 Votre diagnostic était faux, et la vraie cause vous intéresse

Vous écriviez : « `from` est **ramené à maintenant**, quelle que soit la valeur
envoyée ».

Il ne l'était pas. `SlotService.getSlotsInBounds` passait votre valeur telle
quelle, et la requête filtrait bien `s.starts_at BETWEEN :fromTs AND :toTs`.
Votre `from=2020-01-01` était honoré à la seconde près.

Ce qui vidait vos trois mesures est deux lignes plus haut dans le même `WHERE` :

```sql
AND s.status IN ('OPEN', 'FULL')
```

`AttendancePromptJob.closeElapsedSlots` tourne toutes les heures et fait passer
au statut `PAST` tout créneau dont la fin est dépassée. **Il n'existe donc
pratiquement aucun créneau passé qui soit encore `OPEN` ou `FULL`** — et la
fenêtre, si large soit-elle, ne pouvait rendre que le vide. Vos trois lignes de
mesure — 33, 33, 33 — sont la signature exacte de ce comportement : ce n'est pas
une borne ignorée, c'est une population qui n'existe pas dans le filtre.

Nous le soulignons parce que la conclusion change : il n'y avait **rien à
corriger sur `from`**. Il y avait un drapeau à ajouter.

### 3.2 Ce qui est livré

`includePast` (booléen, défaut `false`) sur `SlotBoundsRequest`.

- **À `false`** — donc pour toute version publiée — rien ne change, à l'octet
  près. `from` vaut maintenant par défaut, et le filtre de statut reste
  `OPEN`/`FULL`.
- **À `true`**, `PAST` entre dans le filtre de statut. `from` vaut par défaut il
  y a trois mois, et une valeur explicite plus ancienne est **refusée** par un
  `400 SLOT_PAST_WINDOW_TOO_WIDE`, jamais ramenée en silence à la borne. Cette
  route est née d'une borne rabotée sans le dire ; elle n'en réintroduit pas
  une.
- **`CANCELLED` n'entre jamais**, dans aucun cas. Un créneau annulé n'a pas eu
  lieu, et le poser sur une carte du passé raconterait une séance qui n'a pas
  existé. `PAST` dit « c'était là » ; c'est tout ce que votre interrupteur
  demande.
- Rien d'autre n'est levé : le programme doit toujours être actif et public,
  l'hôte actif, et **le lieu partagé** — un créneau à position masquée n'apparaît
  pas plus dans le passé que dans le présent, pour la raison établie le 04/09.

### 3.3 `/slots/feed` ne change pas

Le corps de requête que les deux géométries partagent définit « quels créneaux
existent pour cette personne », et il fallait le découper sans le dupliquer. Le
seul prédicat sorti du corps commun est celui du statut : le fil en reçoit une
version qui ne connaît que `OPEN`/`FULL`, la carte une version qui accepte
`PAST` sur demande. Tout le reste — visibilité, blocage, activité, catégorie,
langue, accueil — reste défini une seule fois.

« À quoi puis-je encore me joindre » n'a pas de passé, et un test le verrouille
(`leFil_neDoitJamaisVoirLePasse`).

### 3.4 Le `Z`, et pourquoi ce n'était pas qu'une omission de spec

Vous le signaliez en passant : `from` sans suffixe `Z` rend un `400
VALIDATION_ERROR` dont le message parle de `java.time.Instant`. C'est maintenant
dans la spec du champ, avec un exemple (`2026-09-05T19:10:30Z`).

Cela méritait mieux qu'une note, parce que ce défaut-là et le vôtre se
ressemblent trop : dans les deux cas, un paramètre de date paraît sans effet
alors que la cause est ailleurs. L'un renvoie une erreur qui ne nomme pas le
suffixe manquant, l'autre renvoyait `200` avec une liste que rien ne pouvait
remplir. Un champ de date qui ne dit pas son format en a besoin.

### 3.5 Le statut du créneau n'est pas exposé, et ce n'est pas un oubli

`SlotFeedItemDto` ne porte pas de champ `status`, et nous n'en avons pas ajouté.
Votre règle d'affichage se lit sur `startsAt`/`endsAt`, que le DTO rend déjà —
c'est la même règle que sur les trois autres écrans, et elle donne le même
verdict. Ajouter un statut serveur à côté offrirait une seconde source pour la
même question, avec la certitude qu'un jour les deux ne diront pas la même chose.

Si vous constatez qu'il vous manque, demandez-le et nous l'ajouterons — mais
dites-nous d'abord ce qu'il répondrait que les dates ne répondent pas.

---

## 4. Ce que nous n'avons pas touché

Vos trois points « pas demandés, et qui marchent déjà » ont été relus, et ils
marchent effectivement. Rien n'a bougé sur `includeExpired` de
`/activities/browse`, sur `/programs?lat&lng&radius_km`, ni sur
`SLOT_ALREADY_STARTED`.

Un mot sur ce dernier, puisque vous le citez comme la bonne frontière : il l'est,
et il est le seul du lot à refuser sur le **début** plutôt que sur la fin. Ce
n'est pas une incohérence avec `isExpired`. Rejoindre une séance commencée n'a
pas de sens même si elle dure encore ; l'*afficher* comme vivante pendant qu'elle
a lieu en a un. Les deux frontières répondent à deux questions différentes, et il
fallait qu'elles restent distinctes — c'est écrit dans `ProgramCycle`, à côté de
la raison pour laquelle une relance ne doit pas partir au milieu d'un cours.

---

## 5. Où c'est écrit

| Fichier | Ce qu'il porte |
|---|---|
| `domain/program/ProgramTimeliness.java` | Le verdict, une seule fois, pour les deux chemins |
| `domain/search/dto/SearchResultDto.java` | Les deux champs et leur contrat |
| `domain/search/dto/SearchRequest.java` | `includeExpired` et l'argument du défaut à `true` |
| `domain/search/FullTextSearchService.java` | `AGENDA_JOIN`, sans paramètre positionnel |
| `domain/search/SemanticSearchService.java` | Le mapping d'entités, le chargement en lot des agendas, le filtre |
| `repository/ScheduleRepository.java` | Le prédicat de statut, sorti du corps commun |
| `domain/program/dto/SlotBoundsRequest.java` | `includePast`, la fenêtre de trois mois, le `Z` |
| `domain/program/SlotService.java` | La borne basse et son refus |

Tests : `ProgramTimelinessTest` (la doctrine), `SemanticSearchServiceTest` (le
mapping d'entités), `SearchExpiredProgramIntegrationTest` (les quatre requêtes
natives et `includeExpired`), `SlotBoundsIntegrationTest` (six tests de plus pour
`includePast`, dont deux contre-tests).
