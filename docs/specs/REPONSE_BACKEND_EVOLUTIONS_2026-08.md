# Réponse backend aux évolutions demandées par le client mobile (août 2026)

> Réponse à `PROMPT_BACKEND_EVOLUTIONS_2026-08.md`.
> Rédigée le 2026-08-04, contre `master` (`23cfd6d`).
>
> Toute affirmation sur le comportement du serveur est référencée
> `chemin/Fichier.java:ligne`. Ce qui n'a pas été vérifié est signalé comme tel,
> jamais affirmé.

---

## Sommaire

1. [Réponses aux 12 questions ouvertes](#réponses-aux-12-questions-ouvertes)
2. [Arbitrages retenus](#arbitrages-retenus)
3. [Faisabilité par demande](#faisabilité-par-demande)
4. [Corrections au document client](#corrections-au-document-client)
5. [Ce qui est déjà implémenté](#ce-qui-est-déjà-implémenté)
6. [Ordre de livraison proposé](#ordre-de-livraison-proposé)

---

## Réponses aux 12 questions ouvertes

### 1. `/map/activities` — état réel de `nextSessionAt`

**Ni « toujours nul » ni « renseigné sans développer les récurrences ». Un
troisième cas.** `MapService.java:583-585` :

```java
Instant nextSessionAt = representative.getStartsAt() != null
    && representative.getStartsAt().isAfter(now) ? representative.getStartsAt() : null;
```

Le champ porte le `startsAt` du créneau futur le plus proche **à cette
localisation**, et `null` s'il n'y en a aucun. Aucune récurrence n'est
développée.

Ce qui masque le problème : `RecurringSlotRolloverJob` (cron `0 30 * * * *`)
**réécrit `starts_at` en base** pour tout créneau récurrent passé, en l'avançant
du nombre de semaines nécessaire — `ScheduleRepository.rollRecurringSchedulesForward()`,
`+ INTERVAL '7 days'` en dur, sans lire la `RRULE`. En pratique `nextSessionAt`
est donc souvent non nul et futur, mais toujours sur **le même jour de semaine**
que la première séance.

La demande 4 ne se réduit pas à un test de non-régression. Voir §
[Faisabilité, demande 4](#demande-4--récurrences-rfc-5545).

### 2. `ProgramDto.nextSessionAt` développe-t-il les récurrences ?

**Non.** C'est une colonne stockée (`Program.java:81-82`, migration
`V24__add_program_organizer_next_session.sql`), recalculée par
`ProgramService.refreshNextSessionAt()` (`:327-335`) :

```java
scheduleRepository.findByProgramId(program.getId()).stream()
    .map(Schedule::getStartsAt)
    .filter(t -> t != null && t.isAfter(Instant.now()))
    .min(Instant::compareTo)
```

C'est exactement le balayage naïf que le client fait de son côté.

**Ne supprimez pas votre calcul client.** Le `min(valeur serveur, calcul client)`
de `program_providers.dart:533-550` reste le comportement le plus sûr tant que la
demande 4 n'est pas livrée. Le commentaire de `program_models.dart:342-345`
(« fait autorité ») est à corriger : il ne fait pas autorité aujourd'hui.

### 3. `/programs/browse` existe-t-elle ?

**Non.** `rg -i browse src/main` ne renvoie aucun résultat. La constante
`ApiConstants.programsBrowse` (`api_constants.dart:42`) pointe vers une route qui
n'a jamais existé côté serveur ; un appel produirait un 404 `NOT_FOUND`.

Il n'y a rien à étendre : `/activities/browse` serait bien une création. Vous
pouvez retirer la constante côté client.

### 4. `/map/activities` accepte-t-il un rayon que le client n'envoie pas ?

**Non, aucun.** La signature complète est
`getAllActivitiesForMap(Double userLat, Double userLng)`
(`MapController.java:103-108`), et ces deux paramètres ne servent **qu'à calculer
une distance** (`MapService.java:594-597`) — jamais à filtrer. L'implémentation
charge `activityRepository.findAll()` **et** `scheduleRepository.findAllWithActivityDetails()`
(`MapService.java:524-527`), sans borne ni pagination.

Votre demande 5 est donc plus urgente que « préventive » : le coût est déjà en
O(base entière) à chaque ouverture de la carte, indépendamment du nombre de
marqueurs effectivement affichés.

**Mais deux routes de bornage existent déjà**, et le client ne les appelle pas :

| Route | Paramètres | État |
|---|---|---|
| `GET /api/map/bounds` | `north`, `south`, `east`, `west`, `categoryIds`, `activityLevels`, `formats`, `limit` (défaut 100), `offset` (défaut 0) | fonctionnelle — `MapBoundsRequest` |
| `GET /api/map/clusters` | `north`, `south`, `east`, `west`, `zoom` (1-20), `activityId`, `level`, `format` | fonctionnelle — `MapClusterRequest` |

C'est l'Option A **et** un embryon d'Option B. Deux écarts avec votre
proposition : les bornes s'appellent `north/south/east/west` et non
`minLat/maxLat/minLng/maxLng`, et `MapCluster` ne porte que
`(latitude, longitude, count, type)` — **sans** les bounds du cluster, donc sans
recadrage possible au tap.

### 5. Convention de pagination

**Confirmé : `Page<T>` Spring Data, paramètres `page` / `size`.** Utilisée par
sept contrôleurs : `NotificationController:38`, `UserController`,
`ActivityController`, `ReviewController`, `ReportController`,
`ProgressionController`, `PeerRecommendationController`.

`GET /activities/browse` s'y alignera. Pour `POST /search`, votre
`totalCount` / `hasMore` est le bon choix — mais le blocage n'est pas la forme de
l'enveloppe, voir [demande 2](#demande-2--pagination-de-postsearch).

### 6. Ce que `SearchRequest` consomme réellement

`BACKEND_SEARCH_SLOTS.md` §3 est **toujours exact**, et c'est documenté dans le
code lui-même. Le record entier fait quatre champs :

```java
// SearchRequest.java
public record SearchRequest(String query, Double lat, Double lng, Integer radiusMeters) {}
```

avec cette `@Schema` en tête de fichier :

> « Seuls query/lat/lng/radiusMeters sont pris en compte. Il n'y a pas de
> pagination (page/pageSize sont ignorés, pas d'erreur) : chaque appel renvoie sa
> liste complète de résultats en une fois. filters/locale/sort_by/sort_order sont
> également ignorés s'ils sont envoyés. »

**`locale` n'est pas exploité.** La demande 3 ne se réduit pas.

### 7. `SearchFilters` — quels champs honorez-vous ?

**Aucun.** Ils n'existent pas dans le DTO d'entrée. Retirez `location_type`,
`spots_available`, `rating_min`, `language` du modèle client : ne câblez pas de
contrôles pour eux.

### 8. Entité pivot pour l'activité parcourable

Le modèle est à trois niveaux :

| Entité | Porte | Ne porte pas |
|---|---|---|
| `Activity` (référentiel) | `name`, `slug` (unique), `description`, `icon`, `imageUrl`, `category`, `parent` | **aucune coordonnée** |
| `UserActivity` (déclaration) | `user`, `activity`, `visibleOnMap`, `customDescription`, `level`, `format` | lieu, date |
| `Program` → `userActivity`, `Schedule` → `program` | `Schedule.location` (`Point`), `startsAt`, `recurrenceRule` | — |

Autrement dit : `Activity` porte l'image et la description, `UserActivity` porte
l'organisateur, `Schedule` porte le lieu. La carte Explorer « photo + organisateur
+ adresse » n'est **aucune des trois** — c'est bien une projection à construire.

Sur la maille actuelle : `/map/activities` émet **un marqueur par
(activité × localisation arrondie au millième de degré)**
(`MapService.java:551-559`), ni un par activité ni un par `UserActivity`. Quinze
déclarants d'un « Yoga » répartis sur cinq lieux donnent cinq marqueurs. Votre
déduplication par `activityId` (`map_page.dart:786-787`) **perd donc des lieux**.

**Arbitrage retenu : `UserActivity`** (voir [Arbitrages](#arbitrages-retenus)).

### 9. Codes d'erreur stables — en existe-t-il déjà ?

**La structure existait, la sémantique non.** `ErrorResponse(code, message, timestamp)`
est renvoyée par tous les handlers de `GlobalExceptionHandler`, et le `code` était
toujours présent — mais dérivé du **type d'exception Java**, pas du cas métier :

| Exception | HTTP | `code` (avant) |
|---|---|---|
| `ValidationException`, `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
| `ForbiddenException` | 403 | `FORBIDDEN` |
| `IllegalStateException` | 409 | `CONFLICT` |
| `BusinessException` | 422 | `BUSINESS_RULE_VIOLATION` |

« Vous avez déjà rejoint ce créneau » et « ce créneau est complet » sortaient donc
tous deux sous le même code, seul le message français les distinguant.

**C'est corrigé — voir [Ce qui est déjà implémenté](#ce-qui-est-déjà-implémenté).**

### 10. Localisation — d'où viendraient les traductions ?

**Pas de LLM. Des templates codés en dur.** Depuis le commit `1ce2ab3`
(« replace paid Anthropic/OpenAI search pipeline with local trilingual model »),
le pipeline est entièrement local : `SemanticSearchService` utilise
`RuleBasedIntentExtractor` (`:40`), pas d'appel réseau.

`RuleBasedIntentExtractor.clarificationQuestionFor()` (`:242-251`) :

```java
if (normalizedText.matches(".*\\b(i|want|looking|bored)\\b.*"))
    return "What kind of activity would you enjoy today?";
if (normalizedText.matches(".*\\b(ich|will|suche|langweilig)\\b.*"))
    return "Welche Aktivität würde dir heute gefallen?";
return "Quel type d'activité te ferait plaisir aujourd'hui ?";
```

Deux conséquences :

- **Le trilingue existe déjà pour `clarificationQuestion`, mais mal fondé** : la
  langue est devinée à partir de mots-clés présents dans **la requête tapée par
  l'utilisateur**, pas de sa locale. Un germanophone qui tape `yoga` reçoit du
  français. Votre test `search_clarification_language_test.dart` verrouille un
  comportement plus fragile qu'il n'y paraît.
- **`emptyStateActions[].label` est en français concaténé**
  (`SemanticSearchService.java:376-416`), sept gabarits, dont
  `"Élargir la zone de recherche à " + (expanded / 1000) + " km"` et
  `"Voir " + a.getName() + " à la place"`.

Aucune infrastructure i18n dans le projet : pas de `MessageSource`, pas de
`messages*.properties`, aucune occurrence d'`Accept-Language`.

**Réponse à votre inquiétude sur la latence : nulle.** Tout est template ;
traduire, c'est un `MessageSource` Spring et une dizaine de clés. Rien à afficher
sur votre écran de chargement.

### 11. `emptyStateActions[].payload` — le contrat est-il figé ?

Vos observations sont exactes, anomalie comprise. `SemanticSearchService.java` :

| `type` | `payload` | Ligne |
|---|---|---|
| `EXPAND_RADIUS` | `{radiusMeters}`, avec `expanded = min(radius*3, 50000)`, émis seulement si `expanded > radius` | `:376-380` |
| `CREATE_SLOT` | `{activityId}` **ou `{}` vide** quand l'activité n'est pas résolue | `:389-391`, `:409-415` |
| `SET_ALERT` | `{activityId, lat, lng, radiusMeters}` | `:394-398` |
| `SIMILAR_ACTIVITY` | `{activityId, name}`, 3 entrées maximum | `:401-407` |

L'absence intermittente d'`activityId` sur `CREATE_SLOT` que vous aviez notée est
donc réelle et intentionnelle. springdoc est présent (`pom.xml:179`) :
documenter ces payloads dans l'OpenAPI est faisable et sera fait avec la
demande 3.

### 12. Ordre de déploiement

Noté. Rien touchant la carte (demandes 1, 4, 5) ne partira en production sans
préavis.

---

## Arbitrages retenus

| Question | Décision | Conséquence |
|---|---|---|
| **Q8** — maille de l'Explorer | **`UserActivity`** | Une carte = « cette activité-là, chez cette personne-là ». C'est la seule maille qui rende `organizerId` non ambigu, et elle satisfait votre premier critère d'acceptation (deux activités homonymes de deux organisateurs = deux entrées) par construction. `activityId` reste exposé comme attribut de regroupement. |
| **Demande 2** — forme de la pagination | **(a) plafond relevé + pagination en mémoire** | `totalCount` exact dans la limite du plafond serveur, `hasMore` fiable, ordre stable avec tie-break déterministe sur `id`. Au-delà du plafond, `totalCount` est un « au moins N » — nous documenterons le plafond dans l'OpenAPI. |
| **Demande 5** — nouvelles routes ou extension | **Étendre `/map/bounds` et `/map/clusters`** | Pas de `GET /api/map/clusters?minLat…` : on garde `north/south/east/west`. Le client doit s'aligner sur ces noms. On ajoute `truncated`/`totalInBounds`, et les bounds sur `MapCluster`. |

**Conséquence de l'arbitrage 5 pour le client** : la convention `radiusMeters`
(mètres, lowerCamelCase) que vous demandez pour les nouvelles routes s'applique à
`/activities/browse`. Les routes bbox existantes gardent leurs quatre bornes
nommées, qui ne sont pas un rayon et n'entrent pas en conflit avec la convention.

---

## Faisabilité par demande

### Demande 1 — `GET /activities/browse`

**Faisable. C'est une vraie construction, pas un assemblage.**

Le modèle porte tout ce que le contrat demande : `Activity.imageUrl` /
`description` / `icon`, `UserActivity.user` → `organizerId`, et PostGIS
`ST_DWithin` est déjà utilisé (`ScheduleRepository`, requête « autour de moi »).
Les filtres de visibilité existent et sont éprouvés :

```sql
p.status = 'ACTIVE' AND p.is_public = TRUE AND u.is_active = TRUE AND ua.visible_on_map = TRUE
```

Mais **aucune requête existante n'est réutilisable** : c'est une projection
nouvelle sur `Activity × UserActivity × Program × Schedule`.

**Un point du contrat est une capacité nouvelle, pas un correctif.** Votre critère
« une activité en ligne est renvoyée avec `lat`/`lng`/`distanceMeters` à `null` et
non filtrée par le rayon » : aujourd'hui `MapService.java:532` écarte tout créneau
sans `location`, et `:548` écarte toute activité sans créneau localisé. **Les
activités en ligne n'ont jamais figuré sur la carte.** Nous le ferons, mais
sachez que ce n'est pas une régression que nous réparons.

### Demande 2 — pagination de `POST /search`

**Le blocage n'est pas l'enveloppe de réponse, c'est le pipeline.**

`SemanticSearchService.performSearch()` (`:120-175`) n'est pas une requête SQL
paginable, mais une fusion en mémoire :

1. couche taxonomique canonique EN/DE/FR → `LIMIT 20`
2. couche de rappel par embeddings locaux, ou repli plein texte → `LIMIT 20`
3. `mergeResults(…, 20)` avec déduplication par programme
4. filtres `level` / `format` appliqués **après**, en Java (`:152-166`)
5. créneaux prioritaires, puis `MAX_TOTAL_RESULTS = 20` (`:54`) en budget global

Chaque source est plafonnée **avant** la fusion, et des lignes sont retirées
**après**. Le système ne connaît donc jamais le total réel : `totalCount` n'est
pas calculable en l'état, et `page=1` n'a pas de sens tant que la page 0 est déjà
la totalité de ce que le pipeline sait produire.

**Décision (arbitrage (a))** : relever les plafonds, fusionner, trier, découper.
`countsByType` sommera bien à `totalCount` comme vous le demandez, puisque les
deux seront calculés sur la même liste fusionnée.

**Confirmé de notre côté** : personne n'envoie `page` / `pageSize` aujourd'hui, et
ils ne sont pas lus — aucun risque de régression. Nous retenons **`pageSize`**
(lowerCamelCase), alignez le client.

### Demande 3 — `Accept-Language`

**Faisable, sans coût de latence** (cf. réponse 10). Découpage :

- `MessageSource` + `messages_fr.properties` / `_en` / `_de` ;
- `AcceptHeaderLocaleResolver`, langue par défaut `fr` — votre point (d) est ainsi
  satisfait par construction ;
- remplacement des trois `return` de `clarificationQuestionFor` et des sept
  gabarits d'`emptyStateActions` ;
- les valeurs d'énumération (`resultType`, `type`, `status`,
  `EmptyStateActionType`) ne passent pas par le `MessageSource` : elles restent en
  anglais SCREAMING_SNAKE_CASE, comme vous l'exigez.

**Une réserve sur un critère d'acceptation.** Vous demandez à la fois :

- « `Accept-Language: it` retombe sur `en` » ;
- « sans en-tête → français (comportement actuel inchangé) ».

Ce sont deux règles différentes : le repli naturel de Spring est *la locale par
défaut*, qui doit être `fr` pour la non-régression — donc `it` retomberait sur
`fr`, pas sur `en`. C'est implémentable comme vous le voulez (langue non
supportée → `en`, en-tête absent → `fr`), mais il faut le vouloir explicitement.
**Confirmez.**

Le point (c), les codes stables, est **déjà livré**.

### Demande 4 — récurrences RFC 5545

**La demande la plus lourde, et pas pour la raison indiquée.**

`recurrenceRule` n'est **jamais parsé** : il est stocké (`Schedule.java:61-62`,
200 caractères), écrit (`ProgramService.java:203`, `:254`), relu dans les DTO
(`ScheduleDto`, `CreateScheduleRequest`, `UpdateScheduleRequest`), et c'est tout.
Le seul traitement qui l'approche est `RecurringSlotRolloverJob`, qui avance
`starts_at` **de 7 jours en dur, sans lire la règle**.

Conséquence : `FREQ=WEEKLY;BYDAY=MO,WE` est **aussi faux côté serveur que côté
client, et pour la même raison**. Ce n'est pas un calcul à corriger dans une
couche de lecture — il faut :

1. introduire un moteur RFC 5545 (`ical4j` ou `google-rfc-2445`) ;
2. décider du sort de `RecurringSlotRolloverJob`, qui **mute les données en base**
   et deviendrait au mieux redondant, au pire nuisible ;
3. arbitrer si une occurrence développée reste *bookable* (le modèle actuel
   suppose une seule occurrence réservable par `Schedule` — voir le commentaire
   de `ScheduleRepository:77-84`).

C'est la seule demande qui touche le modèle de données. Votre critère
`FREQ=WEEKLY;BYDAY=MO,WE` un mardi → mercredi suivant est bien le test central.

### Demande 5 — bornage / clustering

**Livrée, Options A et B** (voir la section suivante).

**Une correction importante à la réponse 4 ci-dessus, et à notre arbitrage.**
Nous avions annoncé étendre `/map/clusters` pour l'Option B. En ouvrant le code,
cette route s'est révélée agréger des **utilisateurs**, pas des activités :
`MapService.getClusters()` appelle `userRepository.findVisibleUsersInRadius()`
puis `clusterUsers()`. C'est la couche « personnes autour de moi », pas la couche
des marqueurs d'activité dont vous parlez.

Votre Option B décrit sans ambiguïté des clusters d'activités : `categoryIcon`
sur le cluster, et un champ `activities` contenant « les entrées non agrégées,
mêmes champs que `/map/activities` ». Le clustering d'activités a donc été
ajouté à **`/map/activities`** — la route qui produit déjà ces marqueurs, et qui
porte déjà le bornage et `totalInBounds` sur lesquels vos deux critères
d'acceptation reposent. C'est toujours une extension d'une route existante, mais
pas de celle que nous avions annoncée.

`MapCluster` gagne ses bornes dans les deux cas : la route utilisateurs en
profite aussi, elle en avait le même besoin.

Reste : `truncated` / `totalInBounds` sur `/map/bounds`, par symétrie.

### Demande 6 — `DELETE /search/recent/{id}`

**Livrée.** Voir la section suivante.

---

## Ce qui est déjà implémenté

Deux demandes ne dépendaient d'aucun arbitrage et sont en place sur `master`.
Elles sont **additives** : aucune route existante ne change de forme.

### Demande 6 — id stable et suppression unitaire

`GET /api/search/recent` renvoie désormais un `id` :

```jsonc
[
  { "id": "b7e3…", "query": "yoga", "searchedAt": "2026-08-03T18:22:00Z" }
]
```

L'id existait déjà en base (`SearchLog.id`, `@Id @GeneratedValue`) ; seule la
projection JPQL l'omettait. Le tri est désormais
`ORDER BY searchedAt DESC, id DESC`, donc **totalement déterministe** même quand
deux recherches partagent la même milliseconde.

| Route | Comportement |
|---|---|
| `DELETE /api/search/recent/{id}` | **204 No Content**. 404 `SEARCH_HISTORY_ENTRY_NOT_FOUND` si l'entrée n'existe pas **ou** appartient à un autre utilisateur. |
| `DELETE /api/search/recent` | 204, vide l'historique du seul appelant — **existait déjà** avant cette demande. |

**Deux choix à connaître côté client :**

1. **Non idempotent.** Un second `DELETE` sur le même id renvoie **404**, pas 204.
   Vous nous laissiez le choix ; nous prenons celui-ci, cohérent avec « une
   suppression silencieusement sans effet est pire qu'une erreur ».
2. **404 et non 403 pour l'entrée d'autrui**, volontairement indistinguable de
   l'inexistence : l'appartenance d'un id ne doit pas être observable. La
   condition d'appartenance est dans la requête de suppression elle-même
   (`SearchLogRepository.deleteByIdAndUserId`), pas dans un contrôle préalable.

**Point de contrat à trancher de votre côté** : `GET /search/recent` ne
**déduplique pas**. Chaque recherche crée une ligne, donc dix « yoga » = dix
entrées avec dix `id` distincts. C'est le comportement actuel, inchangé. Si vous
voulez une déduplication par requête, dites-le — mais sachez qu'elle rendrait
l'`id` stable à nouveau problématique (que supprime-t-on : la ligne, ou toutes
celles portant la même requête ?).

Votre critère « refaire la recherche supprimée la réintroduit » est satisfait, et
testé.

### Demande 3(c) — codes d'erreur stables

Un enum `ErrorCode` (`shared/exception/ErrorCode.java`) devient la source unique
des valeurs de `ErrorResponse.code`. Les exceptions métier peuvent désormais
porter un code explicite ; `GlobalExceptionHandler` le lit s'il est présent et
**retombe sur le code générique historique sinon**.

**Aucune régression possible** : une exception levée sans code produit exactement
le corps d'erreur qu'elle produisait avant. Seuls les refus explicitement nommés
changent de `code`.

Contrat : **un code n'est jamais traduit et ne change jamais de nom une fois
publié.** Le `message` qui l'accompagne reste destiné à l'utilisateur final et
peut changer de formulation — et, à terme, de langue.

Codes métier nommés à ce jour :

| Domaine | Codes | HTTP |
|---|---|---|
| Créneaux (`SlotService`) | `SLOT_OWN_SLOT`, `SLOT_NOT_OPEN_TO_PARTNERS`, `SLOT_NOT_ACCEPTING_PARTICIPANTS`, `SLOT_ALREADY_STARTED`, `SLOT_FULL` | 400 |
| | `SLOT_ALREADY_JOINED` | 422 |
| | `SLOT_PARTICIPANTS_HOST_ONLY` | 403 |
| Programmes / inscriptions (`ProgramEnrollmentService`) | `PROGRAM_NOT_ACTIVE`, `PROGRAM_ALREADY_ENROLLED`, `PROGRAM_SCHEDULE_MISMATCH`, `PROGRAM_SCHEDULE_FULL`, `ENROLLMENT_ALREADY_LEFT`, `ENROLLMENT_NOT_ACTIVE`, `ENROLLMENT_PROGRESS_OUT_OF_RANGE`, `ACTIVITY_ALREADY_COMPLETED`, `ACTIVITY_ALREADY_SKIPPED` | 400 |
| | `PROGRAM_OWN_PROGRAM`, `ENROLLMENT_NOT_OWNED` | 403 |
| Historique de recherche | `SEARCH_HISTORY_ENTRY_NOT_FOUND` | 404 |

Les codes génériques (`VALIDATION_ERROR`, `NOT_FOUND`, `FORBIDDEN`, `CONFLICT`,
`BUSINESS_RULE_VIOLATION`, `INVALID_CREDENTIALS`, `INVALID_TOKEN`,
`EMAIL_EXISTS`, `RATE_LIMITED`, `INVALID_PARAMETER`, `INVALID_JSON`,
`METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`) restent inchangés partout ailleurs.

**Vous pouvez commencer à traduire `SLOT_ALREADY_JOINED` et consorts dès
maintenant, en gardant `message` comme repli** — c'est exactement le
fonctionnement que vous décriviez au point (c).

**Limite assumée** : `IllegalStateException` → 409 `CONFLICT` ne peut pas porter
de code métier (c'est une exception du JDK, levée depuis plusieurs endroits).
Les 409 restent donc génériques pour l'instant. Signalez-nous les cas que vous
avez besoin de distinguer, nous les convertirons en `BusinessException` nommées.

### Demande 5 — bornage de `GET /map/activities`

Trois paramètres optionnels, tous additifs :

| Paramètre | Type | Notes |
|---|---|---|
| `radiusMeters` | int | mètres, exige `userLat` + `userLng`. Borné **1 – 200 000**. |
| `north` / `south` / `east` / `west` | double | bbox, **les quatre ou aucune** |
| `limit` | int | ≥ 1, **plafonné à 1000** côté serveur |

Rayon et bbox se **cumulent** (intersection) quand les deux sont fournis. Les
noms de bornes sont `north/south/east/west`, ceux déjà employés par
`/map/bounds` et `/map/clusters` — le domaine carte reste cohérent avec
lui-même. C'est l'arbitrage que vous avez validé ; **alignez le client** (votre
proposition disait `minLat/maxLat/minLng/maxLng`).

Réponse enrichie de deux champs, l'enveloppe `{ "activities": [...] }` étant
inchangée :

```jsonc
{
  "activities": [ /* … */ ],
  "defaultCenter": { "lat": 48.86, "lng": 2.34, "zoom": 12 },
  "truncated": true,
  "totalInBounds": 812
}
```

**Le filtre est appliqué en SQL** (PostGIS `ST_DWithin` sur `geography` pour le
rayon, opérateur `&&` sur l'enveloppe pour la bbox), pas après coup sur une liste
déjà chargée — c'était l'objet de la demande. Les ids sont sélectionnés d'abord,
puis repris par la requête à `LEFT JOIN FETCH` existante, ce qui préserve le
chargement anticipé : sans ça, le bornage se serait payé en N+1 sur
`program → userActivity → activity → category`.

**Deux effets de bord positifs, y compris pour les clients déployés :**

1. `activityRepository.findAll()` — un scan complet du référentiel à chaque
   ouverture de carte — **a disparu**. Les activités sans créneau localisé
   étaient de toute façon écartées ; on itère désormais celles qui en ont.
2. **L'ordre des marqueurs devient déterministe** : distance croissante quand la
   position de l'utilisateur est connue, puis `activityId`, puis coordonnées.
   Il ne l'était pas auparavant (`findAll()` sans `ORDER BY`). C'est ce qui rend
   la troncature honnête — elle garde les marqueurs les plus proches, et deux
   appels identiques renvoient la même chose.

**Codes d'erreur** (400, tous nouveaux) : `MAP_RADIUS_REQUIRES_USER_LOCATION`,
`MAP_RADIUS_OUT_OF_RANGE`, `MAP_BOUNDS_INCOMPLETE`, `MAP_BOUNDS_INVALID`,
`MAP_LIMIT_OUT_OF_RANGE`.

**Limite connue** : une bbox à cheval sur l'antiméridien (`west > east`) est
rejetée en `MAP_BOUNDS_INVALID` plutôt que traitée comme deux enveloppes. Dites-
nous si vous en avez besoin.

**Non-régression** : sans aucun de ces paramètres, le contenu de la réponse est
celui d'avant — mêmes marqueurs, mêmes champs, `truncated: false` et
`totalInBounds` égal à la taille de la liste. Seul l'ordre change, et il n'était
pas garanti.

### Demande 5, Option B — agrégation

Un paramètre `zoom` (1–20) de plus sur `GET /map/activities`. Fourni, il agrège
les marqueurs proches ; absent, rien ne change et `clusters` est vide.

```jsonc
{
  "activities": [ /* les marqueurs restés seuls, mêmes champs qu'avant */ ],
  "clusters": [
    { "latitude": 48.86, "longitude": 2.34, "count": 47, "type": "cluster",
      "boundsSouth": 48.85, "boundsNorth": 48.87,
      "boundsWest": 2.32,  "boundsEast": 2.36,
      "categoryIcon": "sports" }
  ],
  "defaultCenter": { "lat": 48.86, "lng": 2.34, "zoom": 12 },
  "truncated": false,
  "totalInBounds": 812
}
```

**Règle d'agrégation** : une cellule de grille portant au moins deux marqueurs
devient un cluster ; une cellule seule laisse son marqueur dans `activities`.
Les deux listes sont donc **disjointes**, ce qui rend votre premier critère
vérifiable — `somme(count) + activities.length === totalInBounds`. Il est testé
aux zooms 3, 7, 12 et 20.

**Nommage des bornes** : `boundsSouth` / `boundsNorth` / `boundsWest` /
`boundsEast`, et non les `boundsMinLat` / `boundsMaxLng` de votre proposition —
même raison que pour la bbox, le domaine carte reste cohérent avec lui-même.

**Les bornes portent l'étendue réelle des membres**, pas la cellule de grille qui
les a regroupés. Recadrer sur la cellule laisserait des marges vides, ou
couperait un membre posé sur son bord.

**Sur votre second critère** — « zoomer jusqu'au niveau maximum ne renvoie plus
que des `activities` » — il est vrai en pratique et testé, mais la formulation
exacte mérite d'être connue : au zoom 20 la maille fait ~1 km, donc deux
activités distinctes de plus d'un kilomètre tombent dans des cellules distinctes
et ressortent non agrégées. **Deux activités à moins d'un kilomètre resteront
agrégées même au zoom maximal.** C'est le comportement voulu — sinon la carte
redessinerait des marqueurs superposés — mais ce n'est pas « aucun cluster,
jamais ».

**Interaction avec `limit`** : la troncature ne porte que sur les marqueurs non
agrégés, un cluster étant déjà une réduction de volume. L'identité de somme
ci-dessus vaut donc quand `truncated` est `false` ; `truncated: true` signale
précisément qu'elle ne vaut plus.

**Code d'erreur** : `MAP_ZOOM_OUT_OF_RANGE` (400) hors de 1–20.

### Tests

Vingt-neuf tests d'intégration, un par critère d'acceptation, verts contre
Postgres + PostGIS + pgvector (Testcontainers) :

- `RecentSearchDeletionIntegrationTest` (6) — id stable entre deux appels, 204 +
  disparition, entrée d'autrui intacte, second DELETE en 404, `DELETE /recent`
  scopé à l'appelant, recherche supprimée puis refaite.
- `BusinessErrorCodeIntegrationTest` (6) — `SLOT_ALREADY_JOINED` en 422,
  `SLOT_OWN_SLOT`, `SLOT_FULL` et `SLOT_NOT_OPEN_TO_PARTNERS` distincts, code
  identique sous `Accept-Language: fr|en|de|it`, et non-régression du
  `NOT_FOUND` générique.
- `MapActivitiesBoundingIntegrationTest` (17) — rayon appliqué (une activité à
  60 km absente pour `radiusMeters=25000`), bbox appliquée, `limit` respecté
  avec `truncated`/`totalInBounds` corrects, ordre stable entre deux appels
  identiques, réponse inchangée sans paramètre, les six refus de validation ;
  puis, pour l'agrégation : deux marqueurs regroupés au zoom 7 avec les bonnes
  bornes, aucun cluster au zoom 20, aucun cluster sans `zoom`, l'identité de
  somme à quatre zooms, et l'invariant de forme des bornes sur `/map/clusters`.
  Les fixtures sont posées au milieu de l'Atlantique (10°N, 30°O) pour être
  isolées des données de seed, ce qui permet des assertions exactes plutôt que
  des « au moins un ».

  Réserve à connaître : le test sur `/map/clusters` ne vérifie qu'un invariant de
  forme (bornes non nulles, centre à l'intérieur, sud ≤ nord), parce que le
  contenu de cette route dépend des utilisateurs présents en base. Les bornes des
  clusters **d'activité**, elles, sont vérifiées sur des valeurs exactes.

---

## Corrections au document client

### 1. `organizerId` est présent sur `/map/activities`

Votre §1 en fait un argument central (« ← LE champ qui manque aujourd'hui »).
Or `MapActivityMarkerDto.java:17` le déclare et `MapService.java:610` le
renseigne (`repUser != null ? repUser.getId() : null`).

Deux hypothèses, que **nous n'avons pas départagées** : soit votre DTO client est
en retard sur ce champ, soit la production est en retard sur `master`. Merci de
rejouer la requête en `curl` avant d'en faire un critère d'acceptation.

### 2. Troisième divergence d'unité, absente de votre tableau

`MapActivityMarkerDto` expose **`distanceKm`**, pas `distanceMeters`
(`MapActivityMarkerDto.java:14`). Votre note de convention en liste deux ; il y en
a trois.

### 3. `programCount` de `/map/activities` ne compte pas des programmes

`MapService.java:609` passe `locationSchedules.size()` — le nombre de **créneaux**
à cette localisation. Un programme unique à trois créneaux hebdomadaires affiche
donc « 3 programmes ».

C'est un bug réel, indépendant de vos six demandes, et le corriger **change un
affichage existant**. Dites-nous si vous voulez qu'on le traite séparément.

### 4. `GET /programs` — deux comportements et une troncature silencieuse

`ProgramController.java:47-57` :

- **sans** `lat`/`lng` : renvoie `getMyPrograms(principal)`, c'est-à-dire **les
  programmes de l'appelant**, pas un catalogue public ;
- **avec** `lat`/`lng` : `findVisibleNearScheduleOrOrganizer(lat, lng, radiusMeters, 100)`
  — plafond **100 en dur** (`ProgramService.java:170`), silencieux.

Votre §1 dit « aucune pagination », ce qui est exact ; il faut ajouter qu'il y a
en plus une troncature invisible.

Les bornes de `radius_km` que vous aviez constatées en production sont confirmées
dans le code : `MIN 0.01`, `MAX 100.0`, défaut `5.0`
(`ProgramService.java:146-148`).

### 5. Numérotation interne décalée

À partir du §4, les renvois (« demande 5 », « demande 6 ») sont décalés d'un cran
par rapport aux titres : `includeExpired` est renvoyé à « demande 5 » alors qu'il
est traité au §4 ; le volume de marqueurs à « demande 6 » alors qu'il est au §5.

### 6. `includePrograms` absent du tableau des paramètres

Le paramètre apparaît dans le JSON d'exemple (`"programs": [...]  // présent
seulement si ?includePrograms=true`) et dans un critère d'acceptation, mais pas
dans le tableau des paramètres du §1.

---

## Ordre de livraison proposé

L'ordre de valeur diffère un peu de vos priorités déclarées.

| | Demande | État | Effort restant | Note |
|---|---|---|---|---|
| 1 | **6** — id + `DELETE /{id}` | ✅ **livré** | — | |
| 2 | **3(c)** — codes d'erreur | ✅ **livré** | — | débloque votre traduction sans dépendre du reste |
| 3 | **5 Option A** — bornage `/map/activities` | ✅ **livré** | — | supprime aussi un `findAll()` non borné en production |
| 4 | **5 Option B** — agrégation + bornes de cluster | ✅ **livré** | — | sur `/map/activities`, pas `/map/clusters` — voir la correction |
| 5 | **3(a,b)** — `Accept-Language` | à faire | moyen | `MessageSource` + ~10 clés, coût runtime nul |
| 6 | **1** — `/activities/browse` | à faire | élevé | projection nouvelle, maille `UserActivity` |
| 7 | **2** — pagination `/search` | à faire | élevé | plafonds relevés + pagination en mémoire |
| 8 | **4** — RRULE | à faire | élevé | seule demande touchant le modèle de données |

### Ce qu'il nous faut de votre côté

1. **Demande 3** — confirmer la règle de repli : langue non supportée → `en`,
   en-tête absent → `fr` ? (les deux règles sont différentes, cf. § Faisabilité)
2. **Demande 6** — voulez-vous une déduplication de `GET /search/recent` par
   requête ? (par défaut : non, comportement inchangé)
3. **Demande 5** — le client s'aligne bien sur `north/south/east/west` (bbox) et
   `boundsSouth/North/West/East` (clusters) ? Et avez-vous besoin des bbox à
   cheval sur l'antiméridien ?
4. **Demande 5, Option B** — le clustering est sur `/map/activities?zoom=`, pas
   sur `/map/clusters` qui agrège des utilisateurs. Confirmez que c'est bien ce
   dont vous avez besoin, ou dites-nous si vous vouliez réellement agréger la
   couche « personnes ».
5. **Correction 1** — un `curl` sur `/map/activities` en production pour savoir si
   `organizerId` y est déjà.
6. **Correction 3** — corrige-t-on `programCount` ? Cela change un affichage
   existant.

### Une question qui nous revient, et qu'on vous signale

`GET /api/map/activities` est **explicitement publique** :
`SecurityConfig.java:63` la déclare en `permitAll()`. Or
`MapActivitiesIntegrationTest.shouldRequireAuthentication` exige un 401 — ce test
échoue depuis avant ces travaux, et il affirme l'inverse d'une ligne de
configuration délibérée.

L'un des deux a tort, et nous ne savons pas lequel : est-ce que la carte doit
être consultable sans compte (auquel cas c'est le test qui est périmé), ou est-ce
que le `permitAll()` est un reste ? Si vous appelez cette route sans jeton depuis
l'app, dites-le-nous — c'est l'information qui tranche.
