# Réponse du client mobile à `REPONSE_BACKEND_EVOLUTIONS_2026-08.md`

> À copier tel quel dans l'instance Claude Code ouverte sur le dépôt backend.
> Rédigée le 2026-08-04, contre la branche client `feat/meetdo-evolution`.
>
> Toute affirmation sur le comportement du serveur est appuyée par une commande
> `curl` rejouable contre `https://pairbackend-production-35fe.up.railway.app/api`.
> Toute affirmation sur le client est référencée `chemin/fichier.dart:ligne`.
> Ce qui n'a pas pu être vérifié est signalé comme tel.

---

## Sommaire

1. [État du déploiement — vérifié en production](#1-état-du-déploiement--vérifié-en-production)
2. [Réponses à « Ce qu'il nous faut de votre côté »](#2-réponses-à--ce-quil-nous-faut-de-votre-côté-)
3. [Ce que nous corrigeons de notre côté](#3-ce-que-nous-corrigeons-de-notre-côté)
4. [Ce que nous attendons ensuite, et dans quel ordre](#4-ce-que-nous-attendons-ensuite-et-dans-quel-ordre)

---

## 1. État du déploiement — vérifié en production

Votre document précise qu'il est « rédigé contre `master` (`23cfd6d`) », sans
prétendre que quoi que ce soit soit déployé. Nous avons donc vérifié
nous-mêmes, parce que **c'est notre dépendance bloquante** : nous ne pouvons
rien brancher contre un contrat qui n'est pas en production.

**Résultat : vos quatre livraisons sont en production, et conformes à votre
description.** Un premier passage plus tôt dans la journée du 4 août ne voyait
encore aucune d'entre elles (69 activités avec ou sans `radiusMeters`,
`clusters: 0` au zoom 7, enveloppe réduite à `defaultCenter`) — le déploiement
est donc intervenu entre les deux mesures. Ce paragraphe remplace ce constat
périmé.

### Bornage — Option A

```bash
BASE=https://pairbackend-production-35fe.up.railway.app/api/map/activities
curl -s "$BASE?userLat=48.8566&userLng=2.3522"                    # 69 activités
curl -s "$BASE?userLat=48.8566&userLng=2.3522&radiusMeters=5000"  # 24 activités
```

Le rayon est **réellement appliqué** (69 → 24). L'enveloppe porte désormais
`activities`, `defaultCenter`, `truncated`, `totalInBounds`, `clusters`.

| Vérification | Commande | Résultat |
|---|---|---|
| `limit` + troncature | `?…&limit=5` | `activities: 5`, `truncated: true`, `totalInBounds: 69` |
| bbox | `?…&north=48.9&south=48.8&east=2.45&west=2.25` | 25 marqueurs, `totalInBounds: 25` |
| bbox partielle | `?…&north=48.9` | 400 `MAP_BOUNDS_INCOMPLETE` |
| rayon sans position | `?radiusMeters=5000` | 400 `MAP_RADIUS_REQUIRES_USER_LOCATION` |
| zoom hors bornes | `?…&zoom=25` | 400 `MAP_ZOOM_OUT_OF_RANGE` |

Les cinq codes d'erreur annoncés sont bien ceux renvoyés, avec l'enveloppe
`{code, message, timestamp}` que nous savons lire (`api_client.dart:232`).

### Agrégation — Option B

```bash
curl -s "$BASE?userLat=48.8566&userLng=2.3522&zoom=7"
```

13 clusters + 10 marqueurs isolés. Premier cluster :

```jsonc
{ "latitude": 48.8547, "longitude": 2.3489, "count": 25, "type": "cluster",
  "boundsSouth": 48.8214, "boundsNorth": 48.8851,
  "boundsWest": 2.2846,  "boundsEast": 2.4005,
  "categoryIcon": "dumbbell" }
```

**Votre invariant tient sur données réelles** : `somme(count) + activities.length`
= 25+…+10 = **69** = `totalInBounds`. C'est le critère d'acceptation que nous
tenions le plus à voir vérifié, parce que c'est lui qui rend le recadrage au tap
implémentable sans état partagé. Sans `zoom`, `clusters` est bien `[]`.

### Ce que nous n'avons pas pu vérifier

`GET /map/bounds`, `GET /search/recent` et `DELETE /search/recent/{id}` répondent
**401** sans jeton (contrairement à `/map/activities`, cf. § sécurité), et nos
tests d'intégration client ne portent pas de compte de service en production.
Nous prenons donc pour acquis, sur votre parole et vos 29 tests d'intégration :
l'`id` stable de `/search/recent`, la suppression unitaire en 204 / 404, et la
symétrie `truncated`/`totalInBounds` **restant à faire** sur `/map/bounds`.

**Une seule demande** : confirmez le SHA déployé et l'horodatage du déploiement.
Nous voulons pouvoir dire, en cas de divergence future, contre quel commit nos
DTO ont été écrits.

---

## 2. Réponses à « Ce qu'il nous faut de votre côté »

### Q1 — Demande 3 : règle de repli des langues

**Oui, confirmé, et oui, nous voulons bien les deux règles distinctes :**

- **langue non supportée** (`Accept-Language: it`) → **`en`** ;
- **en-tête absent** → **`fr`**.

Nous avons lu votre réserve et elle est juste : ce n'est pas le repli naturel de
Spring, et il faut le vouloir explicitement. Nous le voulons explicitement.

La raison est que les deux cas n'ont pas la même population. Un client qui
envoie `Accept-Language: it` est un appareil réel dont l'utilisateur ne lit ni le
français ni l'italien depuis nous : l'anglais est le meilleur repli disponible.
Un client qui n'envoie **aucun** en-tête est, aujourd'hui, un binaire meetDo déjà
déployé — `grep -rn "Accept-Language" lib` ne renvoie toujours rien — et sa
non-régression est le français. Basculer ce cas-là sur l'anglais changerait la
langue de l'app chez des utilisateurs qui n'ont rien demandé.

Concrètement : locale par défaut du `MessageSource` à `fr` pour le cas
« en-tête absent », et une résolution explicite « en-tête présent mais hors de
{fr, en, de} → `en` » **avant** de laisser Spring retomber sur le défaut.

### Q2 — Demande 6 : déduplication de `GET /search/recent`

**Non. Comportement inchangé, ne dédupliquez pas.**

Votre argument nous a convaincus dans l'autre sens : une déduplication
serveur rendrait l'`id` stable ambigu — « supprimer » cesserait d'avoir un objet
unique. Nous préférons un contrat simple (une ligne = un `id` = une suppression)
et une jolie liste, plutôt qu'un contrat ambigu et une jolie liste.

Si le besoin d'affichage se pose — et il se posera, dix « yoga » d'affilée sont
une mauvaise liste de puces — **nous dédupliquerons côté client**, au rendu, en
gardant l'entrée la plus récente et en conservant les `id` masqués pour pouvoir
tout supprimer d'un geste. C'est une décision de présentation, elle nous
appartient.

### Q3 — Demande 5 : nommage des bornes, et antiméridien

**Oui, le client s'aligne** sur `north/south/east/west` (bbox) et
`boundsSouth/boundsNorth/boundsWest/boundsEast` (clusters).

Votre argument de cohérence du domaine carte est le bon : `/map/bounds` et
`/map/clusters` employaient déjà ces noms, notre proposition
`minLat/maxLat/minLng/maxLng` aurait introduit une troisième convention dans une
API qui en compte déjà trop (cf. notre note sur `radiusMeters` /`radius_km` /
`distanceKm`). Nous corrigeons chez nous, pas chez vous.

**Non, nous n'avons pas besoin des bbox à cheval sur l'antiméridien.** Notre
carte ne s'ouvre jamais sur une vue arbitraire : elle s'ouvre sur la position de
l'utilisateur, ou sur le `defaultCenter` que vous renvoyez, avec un rayon
plafonné à 50 km côté client. Le cas `west > east` ne peut pas se produire dans
l'usage réel. **Gardez le rejet en `MAP_BOUNDS_INVALID`** : une erreur explicite
vaut mieux qu'un chemin de code non couvert par les tests. Si nous ouvrons un
jour une vue monde dézoomée, nous reviendrons vers vous — ce n'est pas au
programme.

### Q4 — Demande 5, Option B : clustering sur `/map/activities`, pas `/map/clusters`

**Confirmé : `GET /map/activities?zoom=` est exactement ce dont nous avons
besoin.** Nous voulions agréger la **couche activités**, pas la couche
personnes.

Et merci de l'avoir signalé. Vous aviez annoncé étendre `/map/clusters` ;
livrer ce qui avait été annoncé aurait produit une route qui agrège des
utilisateurs, que nous aurions branchée sur les marqueurs d'activité en nous
demandant pendant deux jours pourquoi les comptes ne tombaient jamais juste.
Ouvrir le code avant d'implémenter, et corriger l'arbitrage plutôt que de tenir
la promesse, est la bonne décision — elle nous a coûté zéro et nous aurait
coûté cher.

Deux conséquences que nous notons :

- notre carte n'appelle **pas** `/map/clusters` et n'a pas vocation à le faire ;
  les bornes que vous avez ajoutées à `MapCluster` pour la couche « personnes »
  ne nous servent pas aujourd'hui, ne les maintenez pas pour nous ;
- votre précision sur le zoom 20 est prise en compte : « deux activités à moins
  d'un kilomètre resteront agrégées même au zoom maximal » est le comportement
  que nous voulons. Notre critère d'acceptation était mal formulé — un cluster
  de 2 vaut mieux que deux pins superposés qu'on ne peut pas taper séparément.
  Nous adapterons l'UI pour que tapper un cluster de faible `count` recadre sur
  ses bornes plutôt que d'essayer de le dissoudre.

### Q5 — Correction 1 : `organizerId` est bien présent

**Votre correction est juste, et notre document avait tort.** Vérifié en
production :

```bash
curl -s "$BASE?userLat=48.8566&userLng=2.3522&radiusMeters=5000" \
  | python3 -c "import sys,json;print(sorted(json.load(sys.stdin)['activities'][0].keys()))"
```

```
['activityId', 'activityName', 'activitySlug', 'address', 'categoryColorRamp',
 'categoryIcon', 'categoryName', 'distanceKm', 'lat', 'lng', 'nextSessionAt',
 'organizerAvatarUrl', 'organizerId', 'organizerName', 'programCount']
```

`organizerId` y figure sur chaque marqueur. Aucune de vos deux hypothèses n'était
la bonne : ce n'était ni un DTO client en retard, ni une production en retard sur
`master`. Notre DTO **lit déjà le champ** — l'erreur venait d'un **commentaire
périmé** (`program_providers.dart:271-275` dans la version que vous avez lue),
qui affirmait l'absence du champ alors que le code juste en dessous le
consommait. Ce commentaire a essaimé jusque dans notre demande d'évolution, où
il servait d'argument central au §1.

Il est corrigé, avec la date et la commande de vérification en toutes lettres
pour que l'erreur ne se reproduise pas. **Rien à changer de votre côté** — et
notre critère d'acceptation « chaque entrée porte un `organizerId` non nul »
reste pertinent pour `/activities/browse` seul, où il porte sur une projection
qui n'existe pas encore.

### Q6 — Correction 3 : `programCount` compte des créneaux

**Oui, corrigez-le. Nous assumons la baisse visible des compteurs.**

La raison est que le chiffre n'est pas « approximatif », il est **faux**, et
qu'il est faux à cinq endroits de l'interface — tous alimentés par le même champ
de `/map/activities` :

| Où | Ce qui est affiché |
|---|---|
| `home_page.dart:344` | `'$programCount programme${…}'` dans les métadonnées de la carte d'accueil |
| `browse_programs_page.dart:1015` | puce `_MetaChip` « N programmes » sur la carte Explorer |
| `activity_detail_sheet.dart:699` | « N programmes disponibles » dans la feuille de détail de la carte |
| `activity_detail_page.dart:64` | **texte d'une confirmation de suppression** : « Le ou les N programmes de cette activité … seront supprimés » |
| `activity_pin_painter.dart:167` | badge numérique dessiné **sur le pin** de la carte (`9+` au-delà de 9) |

Le quatrième cas est celui qui tranche : nous annonçons à un organisateur qu'il
s'apprête à supprimer « 3 programmes » alors qu'il en supprime **un**, à trois
créneaux. Un compteur faux dans une boîte de dialogue destructive n'est pas un
détail d'affichage.

Le cinquième explique pourquoi la baisse sera visible : le badge de pin passe
de « 9+ » à « 1 » sur les activités à créneau hebdomadaire, c'est-à-dire la
majorité. Nous préférons « 1 » vrai à « 9+ » faux, et nous n'avons aucun
mécanisme d'alerte utilisateur à prévoir : personne n'a jamais pu vérifier ce
nombre, donc personne ne s'y est fié.

**Une question, pas une exigence** : exposer en plus un `scheduleCount` séparé
sur `MapActivityMarkerDto` serait-il peu coûteux ? Vous avez déjà la valeur sous
la main — c'est exactement le `locationSchedules.size()` de `MapService.java:609`
qui alimente aujourd'hui `programCount` par erreur. Cela nous permettrait
d'afficher « 1 programme · 3 séances », plus informatif que l'un ou l'autre
seul, et de conserver une densité d'information sur le pin. Si c'est autre chose
qu'un champ additionnel trivial, laissez tomber : `programCount` correct nous
suffit, et nous ne voulons pas transformer un correctif en chantier.

### Question de sécurité — `permitAll()` sur `/map/activities`

**L'information qui tranche : l'app n'appelle jamais cette route sans jeton.**

Trois faits vérifiables de notre côté :

1. **L'`AuthInterceptor` pose systématiquement le Bearer** dès qu'une session
   existe, sans liste d'exclusion (`lib/core/network/api_client.dart:78-81`) :

   ```dart
   final token = await storageService.getAccessToken();
   if (token != null) {
     options.headers['Authorization'] = 'Bearer $token';
   }
   ```

   Aucune route n'est exemptée — la carte reçoit donc un jeton comme toutes les
   autres.

2. **La carte n'est pas atteignable sans authentification.** Le routeur ne
   déclare que `/forgot-password` et `/reset-password` en routes publiques
   (`lib/core/router/app_router.dart:80`) ; tout le reste, `/map` compris,
   redirige vers le mur de connexion. Un utilisateur non authentifié ne peut pas
   arriver sur un écran qui déclenche cet appel.

3. **Votre `permitAll()` est bien effectif**, nous l'avons confirmé :

   ```bash
   curl -s -o /dev/null -w '%{http_code}' \
     "https://pairbackend-production-35fe.up.railway.app/api/map/activities?userLat=48.8&userLng=2.3"
   # → 200
   ```

   Là où `/map/bounds`, `/search/recent`, `/programs/browse` et `POST /search`
   renvoient tous **401** sans jeton.

Conclusion : **le choix « carte publique ou non » vous appartient entièrement.**
Nous ne dépendons pas du `permitAll()`, et nous ne dépendrions pas davantage de
son retrait — dans les deux cas notre appel part avec un Bearer valide.
`MapActivitiesIntegrationTest.shouldRequireAuthentication` ne protège donc aucun
usage réel de notre part ; il ne verrouille rien que nous consommions.

Notre lecture, à titre d'avis et non d'exigence : la route expose des adresses,
des noms d'organisateurs et des avatars, c'est-à-dire des données personnelles
d'utilisateurs qui ont coché `visibleOnMap` pour les autres membres, pas pour un
scraper anonyme. Si la carte publique n'est pas un objectif produit assumé
(page d'atterrissage web, partage d'un lieu sans compte), l'`authenticated()`
nous paraît le défaut le plus sûr — et le test cesserait d'échouer. Mais
tranchez selon vos plans, pas selon nous.

---

## 3. Ce que nous corrigeons de notre côté

Vos six corrections ont été prises en compte. Ce qui suit est déjà en cours sur
notre branche.

| Ce que votre réponse nous apprend | Ce que nous changeons |
|---|---|
| **Réponse 6** — `SearchRequest` ne compte que 4 champs, `locale` n'est pas exploité | Nous retirons `locale` du corps de requête (`search_models.dart:436-439`, envoyé par `search_providers.dart:215`) et passerons par `Accept-Language` quand la demande 3 sera livrée |
| **Réponse 7** — aucun champ de `SearchFilters` n'est honoré | Nous supprimons `locationType`, `spotsAvailable`, `ratingMin`, `language` du modèle client (`search_models.dart:347-359`) plutôt que de leur câbler des contrôles d'interface |
| **Réponse 3** — `/programs/browse` n'a jamais existé | Nous retirons la constante morte `ApiConstants.programsBrowse` (`api_constants.dart:42`) |
| **Réponse 2** — `ProgramDto.nextSessionAt` ne développe pas les récurrences | Nous **gardons** notre `min(valeur serveur, calcul client)` (`program_providers.dart:533-550`) et corrigeons le commentaire qui le disait « faisant autorité » (`program_models.dart:342-345`) |
| **Correction 2** — `distanceKm`, pas `distanceMeters` | Acceptée. Notre note de convention en listait deux divergences, il y en a trois. Notre DTO de marqueur lit déjà `distanceKm`, seule la documentation était fausse |
| **Correction 4** — troncature silencieuse à 100 sur `GET /programs` | Acceptée, et elle nous inquiète (voir ci-dessous) |
| **Corrections 5 et 6** — numérotation décalée à partir du §4, `includePrograms` absent du tableau des paramètres du §1 | Erreurs de rédaction de notre part, reconnues sans réserve. `includePrograms` est bien un paramètre attendu de `/activities/browse`, à ajouter au tableau |
| **Réponse 4 / Demande 2** — vous retenez `pageSize` (lowerCamelCase) | Confirmé, nous alignons le client (`search_models.dart:490` sérialise `page_size` aujourd'hui) |
| **Réponse 5** — `Page<T>` Spring + `page`/`size` est la convention maison | Confirmé pour `/activities/browse` : nous lirons l'enveloppe `PagedModel { content, page }` que nous savons déjà lire (`notification_repository.dart:22`, `:33-46`). `totalCount`/`hasMore` reste réservé à `POST /search`, qui n'est pas une route Spring Data |

### La troncature à 100 sur `GET /programs`

C'est le seul point de vos corrections qui nous laisse un problème ouvert. Un
plafond de 100 **en dur et silencieux** (`ProgramService.java:170`) signifie que
notre écran Explorer, dans une métropole dense, affichera 100 programmes en
présentant cela comme la totalité — sans que ni le client ni l'utilisateur
puissent savoir qu'il en manque.

**Peut-elle au moins exposer un `truncated` ?** Vous venez de faire exactement
cela sur `/map/activities`, avec `truncated` et `totalInBounds`, et c'est
additif. La même paire sur `/programs` nous suffirait : nous n'avons pas besoin
d'un plafond plus haut, nous avons besoin de **savoir** qu'il a mordu, pour le
dire à l'utilisateur au lieu de lui mentir par omission. Si l'enveloppe de
`/programs` est un tableau nu et qu'y ajouter des champs casserait les clients
déployés, dites-le : nous préférons alors que `/activities/browse` naisse
correctement paginé et que nous abandonnions `/programs` pour cet écran.

### Un bug de notre côté, que vous avez trouvé

Votre réponse 8 signale que `/map/activities` émet **un marqueur par
(activité × localisation arrondie au millième de degré)**
(`MapService.java:551-559`), et non un par activité. Notre déduplication par
`activityId` (`map_page.dart:843-856` — « Deduplicate by `activityId` so each
sport gets one pin even with multiple programs ») **perd donc des lieux** :
quinze déclarants d'un « Yoga » sur cinq lieux nous donnent cinq marqueurs, et
nous en affichons un.

C'est un vrai bug chez nous, il est dans notre code depuis l'origine, et il ne
se voit pas — une activité manquante sur une carte ne produit aucune erreur.
Merci de l'avoir vu en répondant à une question qui portait sur autre chose.
Nous le corrigeons : la clé de déduplication devient
`(activityId, lat arrondie, lng arrondie)`.

**Une confirmation nous manque : quelle est la maille exacte de l'arrondi ?**
Nous lisons « millième de degré », soit ~111 m en latitude. Nous avons besoin de
savoir précisément (a) le nombre de décimales, et (b) le mode d'arrondi
(troncature ou arrondi au plus proche), car notre clé doit reproduire la vôtre à
l'identique : une maille plus fine chez nous ne dédupliquerait plus rien, une
maille plus grossière fusionnerait des lieux que vous distinguez. Le mieux
serait que vous **figiez cette maille dans l'OpenAPI** — c'est aujourd'hui un
détail d'implémentation dont dépend le rendu de notre carte.

---

## 4. Ce que nous attendons ensuite, et dans quel ordre

**Votre séquence nous convient telle quelle** : `Accept-Language`, puis
`/activities/browse`, puis la pagination de `/search`, puis les RRULE. Elle place
le moins cher et le plus débloquant en premier, et isole en dernier la seule
demande qui touche le modèle de données. Nous n'avons pas d'objection à
substituer notre ordre de priorité déclaré au vôtre.

Une seule chose passe avant : **le déploiement de ce qui est déjà fait**. Il l'a
été (§1), et c'est ce qui nous permet de commencer. La règle vaut pour la suite :
une demande livrée sur `master` mais non déployée ne débloque rien chez nous, et
nous ne brancherons aucun code contre un contrat que nous ne pouvons pas
interroger en production.

| | Attendu de vous | Nature | Ce que ça débloque chez nous |
|---|---|---|---|
| 0 | SHA + horodatage du déploiement du 4 août | information | épingler nos DTO contre un commit connu |
| 0 | Maille exacte de l'arrondi de localisation (décimales + mode) | information | notre clé de déduplication carte |
| 1 | `programCount` corrigé (+ `scheduleCount` si trivial) | correctif | cinq affichages, dont une confirmation destructive |
| 2 | **Demande 3** — `Accept-Language`, repli `it → en` / absent → `fr` | à faire | l'intercepteur Dio côté client, prêt à poser l'en-tête |
| 3 | `truncated` / `totalInBounds` sur `/map/bounds` (symétrie annoncée) | reste à faire | rien de bloquant, cohérence du domaine carte |
| 4 | `truncated` sur `GET /programs` — **si faisable additivement** | question | dire à l'utilisateur que sa liste est tronquée |
| 5 | **Demande 1** — `/activities/browse`, maille `UserActivity` | à faire | supprime `buildBrowsedActivities` (130 lignes), le `_normName` comme clé étrangère, le 3ᵉ appel au catalogue, le couplage circulaire carte ↔ Explorer |
| 6 | **Demande 2** — pagination `/search` (`pageSize`, `totalCount`, `countsByType`) | à faire | `ListView.builder` + compteurs d'onglets (`search_page.dart:961-991`) |
| 7 | **Demande 4** — RRULE | à faire | supprime notre moteur de récurrence incomplet (`schedule_occurrence.dart:11-13`, `BYDAY` non développé) |

### Ce que nous faisons pendant ce temps

Nous branchons dès maintenant, contre ce qui est déployé : le bornage
(`radiusMeters` réellement transmis par `map_repository.dart`, qui déclare le
paramètre sans le lire aujourd'hui), la lecture de `truncated`/`totalInBounds`,
le clustering au tap avec recadrage sur `boundsSouth/North/West/East`, la
correction de notre clé de déduplication, et la suppression unitaire des
recherches récentes sur l'`id` que vous exposez désormais.

Nous préparons en parallèle nos DTO pour `/activities/browse` sur la maille
`UserActivity` que vous avez arbitrée, sans les câbler.

### Sur votre arbitrage Q8 (`UserActivity`), pour mémoire

Nous le validons. Votre argument — « la seule maille qui rende `organizerId` non
ambigu » — est le bon, et il satisfait par construction notre premier critère
d'acceptation. Nous notons que cela **augmentera** le nombre de cartes affichées
dans l'Explorer par rapport à aujourd'hui (une par déclarant, non plus une par
`activityId` dédupliqué — `program_providers.dart:244-250`), et que ce n'est pas
une régression mais la correction d'un bricolage : la déduplication actuelle
découlait de l'absence de clé de jointure fiable, pas d'une décision produit.

Nous notons également votre avertissement sur les **activités en ligne** : elles
n'ont jamais figuré sur la carte (`MapService.java:532`, `:548` écartent tout
créneau sans `location`), donc les faire apparaître dans `/activities/browse`
avec `lat`/`lng`/`distanceMeters` à `null` est une capacité **nouvelle**, pas un
correctif. C'est bien ainsi que nous l'entendions ; nous le documentons de notre
côté pour que personne ne le compte comme une régression réparée.
