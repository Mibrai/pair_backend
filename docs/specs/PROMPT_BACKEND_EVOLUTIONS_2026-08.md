# Prompt backend — évolutions demandées par le client mobile (août 2026)

> À copier tel quel dans l'instance Claude Code ouverte sur le dépôt backend
> (`org.program.pair`, Spring Boot, base URL `.../api`).
>
> Fait suite à `PROMPT_BACKEND_MEETDO.md` (anomalies) et à
> `BACKEND_SEARCH_SLOTS.md` (indexation des créneaux dans la recherche).
> Celui-ci ne signale **aucun bug** : ce sont six évolutions de contrat, issues
> de l'audit architecture / performance / UX du client Flutter
> (`ios/docs/PLAN_IMPLEMENTATION_2026-08.md`, §4 « Phase 5 — Demandes backend »).
>
> Chaque affirmation sur le comportement actuel du client est référencée
> `chemin/fichier.dart:ligne`. Ce qui n'a pas pu être vérifié est signalé comme
> **hypothèse à confirmer**, jamais affirmé.

---

## Préambule

meetDo est une application mobile Flutter (iOS / Android) qui consomme
intégralement cette API. Son objet : trouver **près de soi** une occasion
concrète de pratiquer une activité — un créneau, un programme, une personne.

Trois chantiers sont en cours côté client et butent chacun sur une limite du
contrat serveur :

1. **Convergence « Découverte »** — la recherche, la carte et l'onglet
   Programmes fusionnent en une seule surface. Quatre implémentations
   différentes de « trouver quelque chose près de moi » cohabitent aujourd'hui,
   dont une **jointure faite dans le client** entre `/programs` et
   `/map/activities` (demande 1).
2. **Internationalisation fr / en / de** — l'app est en cours de traduction
   complète. Une partie des textes affichés à l'utilisateur est **rédigée par
   le serveur** et arrive en français quel que soit l'appareil : ces
   textes-là, le client ne peut pas les traduire (demande 3).
3. **Performance** — pagination absente sur les listes de découverte, volume de
   marqueurs non borné sur la carte (demandes 2 et 6).

**Ce qu'on attend de toi, dans cet ordre :**

1. **Analyser la faisabilité** de chaque demande contre le modèle de données et
   les services existants — plusieurs demandes sont peut-être déjà à moitié
   satisfaites par un champ ou une route que le client ignore (voir §
   « Questions ouvertes »).
2. **Proposer / arbitrer les contrats.** Ceux ci-dessous sont des propositions
   alignées sur les conventions déjà visibles dans vos réponses ; si une autre
   forme est plus naturelle chez vous, dis-le — l'important est la capacité,
   pas le nom des champs. Confirme (ou infirme) chaque contrat **avant**
   d'implémenter, pour que le client prépare ses DTO en parallèle.
3. **Implémenter**, avec un test d'intégration par critère d'acceptation, et
   sans casser les routes existantes : les demandes 1, 2, 5 et 6 doivent être
   **additives** (nouveaux champs nullables, nouveaux paramètres optionnels).

### Note de convention préalable — les unités de distance divergent

Le client doit aujourd'hui jongler entre deux conventions incompatibles pour
la même notion :

| Route | Paramètre | Unité | Casse |
|---|---|---|---|
| `POST /search` | `radiusMeters` | mètres | lowerCamelCase |
| `GET /programs` | `radius_km` | kilomètres | snake_case |
| `GET /map/users` | `radiusMeters` | mètres | lowerCamelCase |
| `GET /map/activities` | *(aucun)* | — | — |

Vérifié : `lib/features/programs/data/program_repository.dart:51` envoie
`radius_km`, `lib/features/map/data/map_repository.dart:41` envoie
`radiusMeters`, `lib/models/search_models.dart:492` sérialise `radiusMeters`.

Pire, les **domaines de validité** diffèrent : `radius_km` de `/programs` est
validé entre `0.01` et `100.0` (au-delà : `400 VALIDATION_ERROR` — confirmé
contre la production), alors que `radiusMeters` de `/search` accepte des
valeurs arbitrairement grandes. Le client entretient une constante dédiée pour
ça, avec son commentaire d'explication :
`lib/features/map/presentation/widgets/map_search_chat_sheet.dart:26-31`
(`_kMaxProgramBrowseRadiusMeters = 100000`), appliquée en clamp ligne 371-373.

**Demande transverse :** toute **nouvelle** route de découverte doit utiliser
`radiusMeters` (mètres, lowerCamelCase) et documenter ses bornes dans l'OpenAPI.
L'harmonisation rétroactive de `/programs` n'est pas demandée ici (elle
casserait le client déployé), mais si vous l'envisagez, prévenez-nous : nous
accepterions volontiers `radiusMeters` en alias toléré sur `/programs`.

---

# Priorité HAUTE

## 1. `GET /activities/browse` — faire la jointure programmes × activités côté serveur

### Le problème côté client

L'onglet Explorer (« Programmes ») affiche **une carte par activité**, avec sa
photo, son organisateur, son nombre de programmes, sa prochaine séance et son
adresse. Aucune route ne renvoie cet objet. Le client le **fabrique** en
croisant deux réponses :

- `GET /map/activities` → les badges d'activité géolocalisés ;
- `GET /programs` → les programmes.

La fonction responsable est `buildBrowsedActivities`
(`lib/features/programs/data/program_providers.dart:207-336`). Elle indexe les
programmes **par nom d'activité normalisé** :

```dart
// lib/features/programs/data/program_providers.dart:191
String _normName(String? s) => (s ?? '').trim().toLowerCase();

// :215 — index des programmes par nom d'activité
byName.putIfAbsent(_normName(p.activityName), () => []).add(p);
// :252 — appariement du badge carte avec ces programmes
final matched = byName[nn] ?? const <Program>[];
```

C'est-à-dire : **le nom est la clé étrangère.** Conséquences, toutes
structurelles :

- deux activités homonymes (« Yoga » à Lille et « Yoga » à Nice, organisateurs
  différents) **fusionnent** en une seule carte, et tous leurs programmes sont
  attribués à la première ;
- « Yoga » et « yoga  » sont réconciliés par la normalisation, mais « Yoga »
  et « Yôga », ou une faute de frappe, **séparent** l'activité de ses
  programmes : la carte affiche alors 0 programme sur une activité qui en a ;
- l'`organizerId` est **absent** de `/map/activities`. Le client le
  documente : « `/map/activities` (MapActivityMarkerDto) expose
  `organizerName` et `organizerAvatarUrl` mais **pas** `organizerId` : l'id ne
  peut venir que du programme apparié. Sans programme apparié, l'auteur reste
  affichable (nom + avatar) mais **non cliquable** »
  (`program_providers.dart:271-275`, code lignes 276-285). Un nom qui ne
  s'apparie pas = un auteur dont on ne peut pas ouvrir le profil ;
- l'image de couverture et la description de l'activité ne sont exposées ni
  par le programme ni par le badge : le client va les chercher dans un
  **troisième** appel, le catalogue `GET /activities`, et les réapparie encore
  une fois par id **puis par nom** (`program_providers.dart:221-237`).

### Le contournement actuel et son coût

| Coût | Preuve |
|---|---|
| **3 requêtes** pour un écran (`/programs`, `/map/activities`, `/activities`) | `program_providers.dart:365-396` |
| **Rayon non maîtrisé** : le client passe 50 000 m à `fetchMapActivities`… | `program_providers.dart:358` |
| …mais la signature **ignore le paramètre** : seuls `userLat`/`userLng` partent sur le réseau | `lib/features/map/data/map_repository.dart:57-71` — `radiusMeters` déclaré ligne 60, jamais lu ; `queryParameters` lignes 66-69 |
| Donc le volume de badges renvoyé est **entièrement décidé par le serveur**, sans borne ni pagination, et l'utilisateur n'a aucun moyen de savoir sur quelle zone il regarde | idem |
| **Aucune pagination** : la totalité des programmes et des badges est chargée, puis jointe et triée sur le thread UI | `program_repository.dart:60-66` (aucun `page`/`size`), `program_providers.dart:391` |
| **Couplage circulaire carte ↔ Explorer** : la carte lit `browseActivitiesProvider` (couche data de l'Explorer) pour savoir quelles activités masquer… | `lib/features/map/presentation/map_page.dart:769`, `:1254` |
| …pendant que l'Explorer lit `mapStateProvider` et `mapRepositoryProvider` (couche data de la carte) pour construire sa liste | `program_providers.dart:346-358` |

> **Correction d'une formulation du plan d'implémentation** : le §4 parle d'un
> « rayon fixe de 50 km non signalé à l'utilisateur ». La valeur `50000` est
> bien écrite en dur (`program_providers.dart:358`), mais elle **n'atteint
> jamais le serveur** (`map_repository.dart:66-69`). Le vrai problème est donc
> pire que décrit : il n'y a **aucun** filtre de rayon sur cette liste.

### Contrat proposé

```
GET /api/activities/browse
```

| Paramètre | Type | Défaut | Notes |
|---|---|---|---|
| `lat` | double | — | requis |
| `lng` | double | — | requis |
| `radiusMeters` | int | *(à définir : 25 000 ?)* | mètres, cf. note de convention |
| `page` | int | `0` | indexé à 0, comme `GET /notifications` |
| `size` | int | `20` | plafonné côté serveur |
| `categoryIds` | string (CSV) | — | optionnel |
| `activityLevels` | string (CSV) | — | optionnel |
| `includeExpired` | bool | `false` | cf. demande 5 |
| `sort` | `distance` \| `relevance` \| `nextSession` | `distance` | |

Réponse — **même enveloppe paginée que `GET /notifications`**, que le client
sait déjà lire (`lib/features/notifications/data/notification_repository.dart:22`,
`:33-46` : `PagedModel { content, page }`) :

```jsonc
{
  "content": [
    {
      "activityId": "0b1e…",              // référentiel Activity — clé de jointure réelle
      "userActivityId": "77c2…",          // nullable
      "activityName": "Yoga vinyasa",
      "activityIcon": "/api/media/files/activity_icon/…",  // nullable
      "imageUrl": "/api/media/files/activity/…",           // ActivityDto.imageUrl
      "description": "…",                                  // nullable
      "categoryId": "a3…",
      "categoryName": "Bien-être",
      "categoryIcon": "yoga",
      "lat": 48.8566,                     // nullable (activité en ligne)
      "lng": 2.3522,                      // nullable
      "address": "12 rue …",              // nullable
      "distanceMeters": 1240.0,           // nullable si lat/lng absents
      "locationType": "IN_PERSON",        // IN_PERSON | ONLINE | HYBRID | null
      "organizerId": "5f2c…",             // ← LE champ qui manque aujourd'hui
      "organizerName": "Lena Müller",
      "organizerAvatarUrl": "/api/media/files/avatar/…",
      "programCount": 3,
      "totalParticipants": 12,
      "nextSessionAt": "2026-08-11T18:30:00Z",  // récurrences développées (demande 5), nullable
      "isExpired": false,
      "programs": [                        // présent seulement si ?includePrograms=true
        { "id": "…", "title": "…", "level": "BEGINNER", "enrolledCount": 4,
          "nextSessionAt": "2026-08-11T18:30:00Z" }
      ]
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 137, "totalPages": 7 }
}
```

Conventions respectées : `lowerCamelCase` partout (comme `SearchResultDto`,
`lib/models/search_models.dart:582-620`), dates **ISO 8601 en chaîne**
(le client parse par `DateTime.tryParse`, jamais un timestamp : cf.
`program_providers.dart:165`, `:544`), distances en **mètres** (`double`),
comme `SearchResultDto.distanceMeters` (`search_models.dart:595`).

`programs` est volontairement optionnel : l'écran de liste n'en a pas besoin,
seule la page de détail d'une activité le consomme. Si l'inclure
inconditionnellement est plus simple chez vous, dites-le — mais alors bornez
la liste (les 3 prochains programmes) plutôt que de tout renvoyer.

### Critères d'acceptation

- [ ] Deux activités de **même nom** appartenant à deux organisateurs
      différents apparaissent comme **deux entrées distinctes**, chacune avec
      ses propres programmes.
- [ ] Chaque entrée porte un `organizerId` non nul dès qu'un organisateur
      existe — y compris pour une activité sans aucun programme.
- [ ] `radiusMeters` est **réellement appliqué** : une activité à 60 km
      n'apparaît pas pour `radiusMeters=25000`.
- [ ] `page=0&size=20` puis `page=1&size=20` ne renvoient **aucun doublon** et
      la concaténation des pages est stable (ordre total déterministe, tie-break
      sur `activityId`).
- [ ] `totalElements` reflète le nombre réel d'activités du rayon, pas la
      taille de la page.
- [ ] Une activité **en ligne** (sans coordonnées) est renvoyée avec
      `lat`/`lng`/`distanceMeters` à `null` — et non filtrée par le rayon.
- [ ] La route reste utilisable **sans** `includePrograms`, et le temps de
      réponse sur un rayon de 25 km reste comparable à celui de
      `GET /map/activities` aujourd'hui.

### Ce que ça supprime côté client

`buildBrowsedActivities` (130 lignes), `_normName` comme clé de jointure, le
troisième appel au catalogue, le couplage circulaire carte ↔ Explorer, et le
recalcul client de l'expiration (voir demande 5). C'est la demande dont le
bénéfice est le plus large.

---

## 2. Pagination de `POST /search`

### Le problème côté client

L'écran de résultats construit **tous ses enfants d'un coup** : un `ListView`
avec `children:` (et non `ListView.builder`), qui instancie chaque carte de
résultat, image comprise, avant le premier pixel affiché —
`lib/features/search/presentation/search_page.dart:961-991`. Le même défaut
existe sur l'écran de requête vide (`search_page.dart:438-511`).

Le client ne peut pas passer en `ListView.builder` avec chargement incrémental
tant que le serveur renvoie tout, ou un nombre inconnu de résultats : sans
`totalCount`, il ne peut ni savoir s'il reste des pages, ni afficher un
compteur par onglet (`Tout (15) / Personnes (3) / Programmes (12)`, prévu au
§1.6.1 du plan).

### Le contournement actuel et son coût

Il n'y en a pas — c'est une limite subie. Le modèle client **porte déjà** les
champs de pagination, envoyés puis ignorés : `SearchRequest.page`,
`pageSize`, `limit` (`lib/models/search_models.dart:425-427`, sérialisés
`page` / `page_size` / `limit` ligne 489-491). Ce point a déjà été signalé le
25 juillet dans `BACKEND_SEARCH_SLOTS.md` §3 (« un client qui envoie
`page: 2` croit aujourd'hui paginer ») — sans réponse connue à ce jour.

À noter : le client ne les renseigne actuellement **pas** dans le chemin réel
(`runSearchWithFallback`, `lib/features/search/data/search_providers.dart:110-119`
et `:129-138`, construit un `SearchRequest` sans `page`) — donc **aucun risque
de régression** si vous les implémentez : personne ne les envoie encore.

Second coût, moins visible : le repli « chercher partout » relance une
**deuxième** requête `/search` complète avec
`radiusMeters = 20 000 000` (`search_providers.dart:41`, `:129-138`). Sur un
corpus qui grandit, cette requête non paginée est la plus lourde de
l'application.

### Contrat proposé

Ajouter à `SearchRequest` (corps `POST /api/search`) :

```jsonc
{
  "query": "yoga",
  "lat": 48.8566, "lng": 2.3522,
  "radiusMeters": 5000,
  "locale": "fr",
  "page": 0,          // ← indexé à 0
  "pageSize": 20      // ← lowerCamelCase, pour rester cohérent avec radiusMeters
}
```

> Le client envoie aujourd'hui `page_size` en snake_case
> (`search_models.dart:490`). C'est une incohérence de notre côté avec
> `radiusMeters`. **Choisissez `pageSize`** : nous alignons le client, le champ
> n'étant lu par personne aujourd'hui. Dites-nous simplement lequel vous
> retenez.

Et à `SearchResponse` (champs **additifs**, donc rétrocompatibles) :

```jsonc
{
  "type": "results",
  "results": [ /* … inchangé … */ ],
  "totalCount": 137,          // total toutes catégories, dans le rayon demandé
  "page": 0,
  "pageSize": 20,
  "hasMore": true,
  "countsByType": {           // alimente les compteurs d'onglets
    "user": 3,
    "program": 12,
    "slot": 7
  }
}
```

`countsByType` doit compter le **total** par type, pas la page courante — sinon
l'onglet « Personnes (3) » afficherait 3 sur la page 1 puis 0 sur la page 2.
Les clés reprennent l'énumération `resultType` (`user`, `program`, `slot` —
cf. `lib/models/search_models.dart:96-118` et `BACKEND_SEARCH_SLOTS.md` §2.1).

### Critères d'acceptation

- [ ] `page=0&pageSize=20` puis `page=1&pageSize=20` sur la même requête ne
      renvoient aucun doublon ; l'ordre par `relevanceScore` décroissant est
      **stable** entre les pages (tie-break déterministe sur `id`).
- [ ] `totalCount` est constant d'une page à l'autre pour une même requête.
- [ ] `hasMore` vaut `false` sur la dernière page, y compris quand
      `totalCount` est un multiple exact de `pageSize`.
- [ ] Une requête **sans** `page`/`pageSize` se comporte exactement comme
      aujourd'hui (défaut serveur), avec `totalCount` néanmoins renseigné.
- [ ] `countsByType` somme à `totalCount`.
- [ ] Les réponses de type `clarification` et `empty` restent inchangées
      (pas de pagination sur une réponse sans résultat).
- [ ] Le schéma OpenAPI documente les nouveaux champs.

---

## 3. Localiser les textes destinés à l'utilisateur final (`Accept-Language`)

### Le problème côté client

L'application cible **trois langues de plein droit : fr, en, de**
(`lib/l10n/app_fr.arb`, `app_en.arb`, `app_de.arb` ; `l10n.yaml` avec
`template-arb-file: app_en.arb`). La traduction de l'interface est en cours.

Mais une partie des textes affichés **n'appartient pas au client** : ils sont
rédigés par vous et affichés **verbatim**. Le client a explicitement décidé de
les afficher tels quels plutôt que de les remplacer par un texte fixe — c'est
verrouillé par un test :
`test/search_clarification_language_test.dart:9-18` (« `clarificationQuestion`
renvoyé par le backend peut désormais arriver en fr/en/de selon la langue
détectée côté serveur — le client l'affiche tel quel »).

Autrement dit : **le client a fait sa part et attend la vôtre.** Aujourd'hui,
un utilisateur germanophone voit une interface allemande dans laquelle
apparaissent, en français, les textes suivants :

| Texte | Où il est consommé | Preuve |
|---|---|---|
| `SearchResponse.clarificationQuestion` | bulle de clarification | `search_page.dart:144`, `map_search_chat_sheet.dart:924` |
| `SearchResponse.suggestedAlternatives[]` | puces cliquables | `search_page.dart:147`, `:154`, `map_search_chat_sheet.dart:931`, `:1092` |
| `EmptyStateAction.label` | libellé du bouton d'action | `lib/models/search_models.dart:199-202` (le libellé serveur est préféré, avec repli client) |
| Messages d'erreur métier **400 / 403 / 409 / 422** | snackbar d'erreur | `lib/core/network/api_client.dart:262-277` |

Le cas des erreurs mérite un mot : le client **préfère délibérément** votre
message au sien, avec ce commentaire —

```dart
// lib/core/network/api_client.dart:258-262
// Refus métier : le serveur explique pourquoi, et son message est
// rédigé pour l'utilisateur final — on le préfère au libellé par
// défaut de l'exception. Sans ce mapping, un 422 « vous avez déjà
// rejoint ce créneau » ressortait en « Une erreur est survenue ».
```

C'est le bon comportement : vous seuls connaissez la raison exacte du refus.
Mais il transforme mécaniquement chaque message serveur en chaîne
d'interface — donc en chaîne à traduire.

### Le contournement actuel et son coût

Aucun contournement n'existe, et **aucun n'est possible** : traduire côté
client supposerait de reconnaître un texte libre pour le remplacer, ce qui
casse au premier changement de formulation chez vous.

Ce qui existe déjà : `SearchRequest.locale` (`'fr'` / `'en'` / `'de'`),
calculé depuis la langue de l'appareil et envoyé sur **`/search` uniquement** —
`lib/core/utils/locale_hint.dart:4-13`, appliqué
`lib/features/search/data/search_providers.dart:215`. Sa documentation dit
qu'il s'agit d'un *indice de classement cross-lingue*, pas d'une langue de
rendu (`search_models.dart:436-439`) : c'est peut-être déjà exploitable, mais
ce n'est pas ce pour quoi il a été conçu.

Aucune autre requête ne transmet la langue : `grep -rn "Accept-Language" lib`
ne renvoie **rien**. Le client ajoutera un intercepteur Dio qui pose
`Accept-Language` sur **toutes** les requêtes dès que le serveur l'honore.

### Contrat proposé

**a) Honorer l'en-tête HTTP standard `Accept-Language`** sur toutes les routes
qui renvoient du texte destiné à l'utilisateur final :

```
Accept-Language: de-DE, de;q=0.9, en;q=0.8
```

Résolution attendue : première langue supportée de la liste → sinon `en` →
sinon la langue par défaut du serveur. Langues à supporter : **fr, en, de**
(les trois de `searchLocaleHint`, `lib/core/utils/locale_hint.dart:4`).

**b) Périmètre minimal**, par ordre de visibilité :

1. `clarificationQuestion` et `suggestedAlternatives[]` de `POST /search` ;
2. `emptyStateActions[].label` de `POST /search` ;
3. le champ `message` des erreurs **400, 403, 409, 422** ;
4. *(souhaitable)* les libellés de catégories et de niveaux, s'ils sont
   renvoyés en clair — **à confirmer**, cf. Questions ouvertes.

**c) Forme des erreurs.** Ne changez pas la structure, seulement la langue du
`message`. Le client lit, dans l'ordre, `message`, `error`, `detail`
(`api_client.dart:232`). Idéalement, ajoutez à côté un **code stable** :

```jsonc
{
  "code": "SLOT_ALREADY_JOINED",           // stable, jamais traduit
  "message": "Du bist diesem Slot bereits beigetreten.",  // suivant Accept-Language
  "timestamp": "2026-08-04T10:12:00Z"
}
```

Le `code` nous permettrait, à terme, de traduire nous-mêmes les cas que nous
savons nommer, et de garder votre `message` comme repli pour tous les autres.
C'est la solution la plus robuste des deux — si vous ne deviez en faire qu'une,
**faites le `code`**.

**d) Ne pas régresser :** en l'absence d'`Accept-Language`, le comportement
actuel (français) doit être conservé. Les clients déjà déployés ne l'envoient
pas.

### Critères d'acceptation

- [ ] `POST /api/search` avec `Accept-Language: de` sur une requête ambiguë
      renvoie un `clarificationQuestion` **en allemand**.
- [ ] Même requête avec `Accept-Language: en` → anglais ; sans en-tête →
      français (comportement actuel inchangé).
- [ ] `emptyStateActions[].label` suit la même langue que
      `clarificationQuestion` dans la même réponse.
- [ ] Un refus métier 409 / 422 (ex. « déjà inscrit à ce créneau ») renvoie son
      `message` dans la langue demandée, et un `code` stable **identique** quelle
      que soit la langue.
- [ ] Une langue non supportée (`Accept-Language: it`) retombe sur `en` (ou le
      défaut serveur), jamais sur une erreur ni une chaîne vide.
- [ ] Les valeurs d'énumération (`resultType`, `type`, `status`,
      `EmptyStateActionType`) restent en **anglais / SCREAMING_SNAKE_CASE** et
      ne sont **jamais** traduites — le client les parse
      (`search_models.dart:151-156`, `:106-115`).

---

# Priorité MOYENNE

## 4. `nextSessionAt` développant les récurrences sur `/map/activities`

### Le problème côté client

Un créneau hebdomadaire conserve le `startsAt` de sa **première** séance :
comparer brutalement ce `startsAt` à « maintenant » déclare terminée une
activité qui a lieu chaque semaine. Le client a donc écrit son propre moteur
de récurrence RFC 5545 :

`lib/features/programs/domain/schedule_occurrence.dart:14-38` — `nextOccurrence`
gère `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL`, `UNTIL`, `COUNT`, avec un
garde-fou de 1 000 itérations (ligne 28) contre les règles mal formées.

**Ce moteur est volontairement incomplet, et sa limite est documentée** :

```dart
// schedule_occurrence.dart:11-13
// `BYDAY` n'est **pas** développé au-delà du jour de la première
// séance : un « lundi et mercredi » ne renverra que les lundis.
```

Un cours « lundi et mercredi » est donc affiché avec une prochaine séance
fausse — un mercredi n'est jamais proposé. Ce n'est réparable qu'en dupliquant
côté client un moteur iCalendar complet, ce qui n'a pas de sens : vous avez la
règle **et** le calendrier.

### Le contournement actuel et son coût

`BrowsedActivity.isExpired`
(`lib/features/programs/data/program_providers.dart:119-128`) parcourt tous les
programmes × tous leurs créneaux et appelle `nextOccurrence` sur chacun. Ce
calcul, qui appartient à l'onglet Programmes, est ce dont **la carte** dépend
pour masquer ses pins :

```dart
// lib/features/map/presentation/map_page.dart:129-133
// Le marqueur /map/activities ne suffit pas à le savoir (nextSessionAt reste
// null et ignore la récurrence), donc on dérive l'info du croisement
// badges+programmes de l'Explorer (browseActivitiesProvider).
// Clés par id ET par nom normalisé.
```

Concrètement (`map_page.dart:725-750`, `:769-770`, `:1254`) : la carte
maintient deux ensembles, `_expiredActivityIds` et `_expiredActivityNames`, et
filtre ses marqueurs dessus — **encore une fois par nom normalisé** pour les
badges dont l'id ne s'apparie pas (`map_page.dart:746-750`). La carte ne peut
donc pas s'afficher correctement sans que la couche data de l'onglet
Programmes ait résolu.

Coût : le couplage circulaire décrit en demande 1, une source de vérité
dupliquée, et une réponse fausse sur toute règle `BYDAY` multi-jours.

> **Observation à confirmer côté serveur.** Le champ `nextSessionAt` **existe
> déjà** dans la réponse de `/map/activities` : le client le lit
> (`lib/features/map/presentation/widgets/activity_detail_sheet.dart:153`).
> Le commentaire de `map_page.dart:131` affirme qu'il « reste null et ignore
> la récurrence ». Nous n'avons **pas** rejoué la requête en `curl` pour
> distinguer les deux cas : *(a)* le champ est toujours `null`, *(b)* il est
> renseigné mais ne développe pas les récurrences. **Le premier point à
> vérifier de votre côté est celui-là** — si c'est *(b)*, la demande se réduit
> à corriger le calcul.
>
> Note connexe : `ProgramDto.nextSessionAt` (`/programs/{id}`) est décrit côté
> client comme faisant « autorité sur un balayage client de `schedules`, qui
> ignore les récurrences » (`lib/models/program_models.dart:342-345`) — mais le
> code, lui, prend malgré tout le **minimum** entre votre valeur et son propre
> calcul (`program_providers.dart:533-550`). Nous ne savons donc pas si
> `ProgramDto.nextSessionAt` développe les récurrences. **À confirmer aussi.**

### Contrat proposé

Sur `GET /api/map/activities` (et sur `GET /activities/browse` de la demande 1,
qui a vocation à le remplacer), chaque entrée porte :

```jsonc
{
  "nextSessionAt": "2026-08-11T18:30:00Z",   // ISO 8601 UTC, récurrences développées
  "isExpired": false                          // aucune occurrence à venir sur AUCUN
                                              // créneau de l'activité
}
```

Sémantique attendue, alignée sur ce que le client calcule aujourd'hui
(`program_providers.dart:112-128`) :

- `nextSessionAt` = la **plus proche occurrence à venir**, tous programmes et
  tous créneaux de l'activité confondus, **récurrences développées**
  (`RRULE` complète : `BYDAY` multi-jours inclus, `UNTIL`, `COUNT`,
  `INTERVAL`, et les exceptions `EXDATE` si vous les gérez).
- `isExpired = true` **uniquement** si l'activité est *datée* (au moins un
  créneau déclaré) **et** qu'aucune occurrence future n'existe. Une activité
  **sans aucun créneau** (en ligne, ou badge sans séance) n'est **jamais**
  expirée — c'est la règle actuelle du client (`program_providers.dart:112-116`)
  et elle doit être conservée, sinon toutes les activités en ligne
  disparaîtraient de la carte.
- `nextSessionAt` est `null` quand `isExpired` est `true`, ou quand l'activité
  n'a aucun créneau.

Et, corollaire, sur `GET /activities/browse` : un paramètre `includeExpired`
(défaut `false`) pour que le serveur filtre lui-même — c'est ce que la carte
veut réellement.

### Critères d'acceptation

- [ ] Un créneau `FREQ=WEEKLY` dont le `startsAt` est passé remonte un
      `nextSessionAt` **futur**, pas `null`.
- [ ] Un créneau `FREQ=WEEKLY;BYDAY=MO,WE` un mardi remonte le **mercredi**
      suivant, pas le lundi suivant. *(C'est le cas que le client ne sait pas
      traiter — le test de non-régression le plus important.)*
- [ ] Un créneau `UNTIL` dépassé, ou dont le `COUNT` est épuisé, donne
      `nextSessionAt: null` et `isExpired: true`.
- [ ] Une activité sans aucun créneau : `nextSessionAt: null`,
      `isExpired: false`.
- [ ] `isExpired: true` ⇒ `nextSessionAt: null`, sans exception.
- [ ] Les dates sont en **UTC ISO 8601 avec suffixe `Z`** (le client fait
      `DateTime.parse(...).toLocal()` — `activity_detail_sheet.dart:162`,
      `program_providers.dart:165` : une date sans fuseau est interprétée comme
      **locale à l'appareil**, ce qui décale l'affichage).

---

## 5. Clustering serveur ou marqueurs par tuile

### Le problème côté client

`GoogleMap` reçoit un `Set<Marker>` complet à chaque reconstruction. Chaque
marqueur d'activité et de créneau est une **image dessinée à la volée**
(gradient, photo décodée, icône, encodage PNG) — d'où un cache d'icônes borné
introduit en semaine 1 (`lib/features/map/presentation/widgets/pin_icon_cache.dart`)
et une construction parallélisée pour ne pas payer la somme des attentes
(`map_page.dart:795-797` pour les activités, `:645-651` pour les créneaux).

Le point dur restant : **le client ne contrôle pas le volume reçu.**
`GET /map/activities` n'accepte aucun rayon (`map_repository.dart:66-69`, cf.
demande 1) ni aucune pagination. Le nombre de marqueurs est une fonction du
contenu de votre base, pas d'un paramètre.

Les mitigations déjà en place ne réduisent le volume **qu'après** réception :

- filtrage des activités sans programme (`map_page.dart:601-604`) ;
- filtrage des activités expirées (`map_page.dart:780`) ;
- déduplication par `activityId` (`map_page.dart:786-787`) ;
- exclusion des créneaux sans localisation précise (`map_page.dart:640`).

### Le contournement actuel et son coût

Aucun clustering aujourd'hui. Le plan d'implémentation retient, si le backend
ne suit pas, un clustering **client** (`google_maps_cluster_manager`) comme
repli acceptable — c'est notre position de repli, pas un blocage.

> **Hypothèse à confirmer, non mesurée.** Le seuil « au-delà de ~200 marqueurs,
> `GoogleMap` chute » vient du §4 du plan d'implémentation ; nous ne l'avons
> **pas** mesuré au profileur sur un jeu de données réel, et la base de
> production actuelle ne semble pas l'atteindre. Cette demande est donc
> **préventive**. Traitez-la comme telle : elle est légitime le jour où une
> métropole dense est peuplée, pas urgente aujourd'hui.

### Contrat proposé

Deux options, par ordre de préférence.

**Option A — bornage simple (peu coûteux, à faire de toute façon).**
Sur `GET /map/activities` et `GET /activities/browse` : accepter
`radiusMeters` (cf. demande 1) **et** une bbox, plus un plafond serveur :

```
GET /api/map/activities?minLat&minLng&maxLat&maxLng&limit=300
```

Réponse enrichie d'un indicateur de troncature, pour que le client puisse le
dire à l'utilisateur au lieu d'afficher silencieusement une carte partielle :

```jsonc
{
  "activities": [ /* … */ ],
  "truncated": true,
  "totalInBounds": 812
}
```

L'enveloppe `{ "activities": [...] }` existe déjà et est celle que le client
sait lire (`map_repository.dart:72-79`) : les deux champs sont **additifs**.

**Option B — clustering serveur.** Une route qui agrège par tuile selon le
zoom :

```
GET /api/map/clusters?minLat&minLng&maxLat&maxLng&zoom=12
```

```jsonc
{
  "clusters": [
    { "lat": 48.86, "lng": 2.34, "count": 47, "categoryIcon": "sports",
      "boundsMinLat": 48.85, "boundsMinLng": 2.32,
      "boundsMaxLat": 48.87, "boundsMaxLng": 2.36 }
  ],
  "activities": [ /* entrées non agrégées, mêmes champs que /map/activities */ ]
}
```

Un cluster tapé ⇒ le client recadre sur ses bounds et recharge : c'est le
comportement standard, et il n'exige aucun état côté serveur.

**Si l'Option B est trop coûteuse, faites l'Option A et dites-le-nous** : nous
prenons alors le clustering client. Ce dont nous avons besoin en priorité,
c'est du **bornage** (`radiusMeters` / bbox / `limit`), pas de l'agrégation.

### Critères d'acceptation

- [ ] `GET /map/activities` accepte un bornage géographique (rayon **ou** bbox)
      et l'applique réellement.
- [ ] Un `limit` explicite est respecté, et `truncated` vaut `true` quand des
      résultats ont été écartés.
- [ ] Sans aucun de ces paramètres, la réponse est **identique à aujourd'hui**
      (clients déployés).
- [ ] *(Option B)* La somme des `count` des clusters plus le nombre
      d'`activities` non agrégées égale `totalInBounds`.
- [ ] *(Option B)* Zoomer jusqu'au niveau maximum ne renvoie plus que des
      `activities`, aucun cluster.

---

# Priorité BASSE

## 6. `DELETE /search/recent/{id}` — et d'abord, un identifiant

### Le problème côté client

L'écran de recherche affiche les recherches récentes en puces cliquables
(`search_page.dart:454-465`). Il n'y a aucun moyen d'en **supprimer** une :
une requête tapée par erreur, ou qu'on ne souhaite pas voir réapparaître,
reste affichée indéfiniment.

### Le contournement actuel et son coût

Il n'y en a pas, et l'obstacle est plus profond qu'une route manquante :
**la réponse actuelle ne porte pas d'identifiant.** Le client en **fabrique**
un, par concaténation :

```dart
// lib/features/search/data/search_repository.dart:102-116 — _parseRecentSearch
// « The backend returns { query, searchedAt } for recent searches. »
return RecentSearch(
  id: '${json['query']}_$timestamp',   // ← id synthétique, purement client
  query: json['query'] as String,
  type: SearchType.all,
  timestamp: timestamp,
);
```

Et quand `searchedAt` est absent, le repli est `DateTime.now()` — l'id change
donc **à chaque chargement**. Un `DELETE /search/recent/{id}` construit sur cet
identifiant ne peut pas fonctionner : la demande porte donc d'abord sur
l'**exposition d'un id stable**, ensuite seulement sur la route de suppression.

Le modèle `RecentSearch` prévoit par ailleurs `type` et `filters`
(`lib/models/search_models.dart:799-812`) qui ne sont **jamais renseignés** par
le serveur : `type` est forcé à `SearchType.all` et `filters` reste `null`.
Si ces champs n'ont pas vocation à exister, dites-le : nous les retirerons du
modèle client.

### Contrat proposé

**a)** Ajouter un `id` stable à chaque entrée de `GET /api/search/recent` :

```jsonc
[
  { "id": "b7e3…", "query": "yoga", "searchedAt": "2026-08-03T18:22:00Z" }
]
```

**b)** Deux routes de suppression :

```
DELETE /api/search/recent/{id}   → 204 No Content
DELETE /api/search/recent        → 204 No Content   (« tout effacer »)
```

Règles :

- 404 si l'`id` n'existe pas ; **403 ou 404 si l'entrée appartient à un autre
  utilisateur** — jamais 200 (une suppression silencieusement sans effet est
  pire qu'une erreur) ;
- l'historique est propre à l'utilisateur authentifié, comme `GET`
  aujourd'hui ;
- idempotence acceptable : un second `DELETE` sur le même id peut renvoyer 204
  plutôt que 404, dites-nous simplement lequel vous choisissez.

### Critères d'acceptation

- [ ] `GET /search/recent` renvoie un `id` **stable entre deux appels** pour la
      même entrée.
- [ ] `DELETE /search/recent/{id}` renvoie 204, et l'entrée a disparu du `GET`
      suivant.
- [ ] Supprimer l'entrée d'un **autre** utilisateur est impossible (403/404).
- [ ] `DELETE /search/recent` vide l'historique du seul appelant.
- [ ] Refaire la recherche supprimée la réintroduit normalement dans
      l'historique (la suppression n'est pas une liste noire).

---

# Questions ouvertes

Ce que le client ne peut pas trancher seul. Réponds-y **avant** d'implémenter :
plusieurs demandes changent de forme, ou disparaissent, selon la réponse.

### Sur l'existant

1. **`/map/activities` : quel est l'état réel de `nextSessionAt` ?** Toujours
   `null`, ou renseigné sans développer les récurrences ? Le client le lit
   (`activity_detail_sheet.dart:153`) mais son commentaire de la carte affirme
   qu'il est nul (`map_page.dart:131`) — nous n'avons pas rejoué la requête.
   *Si le champ est correct, la demande 4 se réduit à un test de
   non-régression.*
2. **`ProgramDto.nextSessionAt` développe-t-il les récurrences ?** Le client
   prend aujourd'hui le minimum entre votre valeur et son propre calcul
   (`program_providers.dart:533-550`), ce qui est un aveu de doute. Si votre
   valeur fait autorité, nous supprimons le calcul client.
3. **Une jointure activité × programmes existe-t-elle déjà ?**
   `ApiConstants` déclare `/programs/browse`
   (`lib/core/config/api_constants.dart:42` — `programsBrowse`) qui n'est
   **appelée par aucun code** du client. Que renvoie-t-elle ? Si elle est
   proche de ce que décrit la demande 1, mieux vaut l'étendre que créer
   `/activities/browse`.
4. **`/map/activities` accepte-t-il un rayon que le client n'envoie pas ?**
   Le client ne transmet que `userLat`/`userLng` (`map_repository.dart:66-69`).
   Existe-t-il déjà un `radiusMeters`, une bbox ou un `limit` documentés ? Si
   oui, la demande 5 option A est déjà satisfaite et il suffit de nous le dire.
5. **La pagination : quelle forme faites-vous autorité ?** `GET /notifications`
   renvoie une `PagedModel { content, page }` Spring et prend `page`/`size`
   (constaté : `notification_repository.dart:22`, `:33`). D'autres routes
   (`/alerts`, `/attendances/pending`) semblent aussi renvoyer un `content`
   (`alert_repository.dart:103`, `attendance_repository.dart:101`).
   Confirmez-vous que **`page`/`size` + `PagedModel`** est la convention
   maison ? Les contrats des demandes 1 et 2 s'y alignent, mais `POST /search`
   n'est pas une route Spring Data classique — d'où le `totalCount`/`hasMore`
   proposé plutôt que l'enveloppe. Tranchez.
6. **`SearchRequest` : que consommez-vous réellement aujourd'hui ?**
   `BACKEND_SEARCH_SLOTS.md` §3 (25 juillet) indiquait que seuls `query`,
   `lat`, `lng`, `radiusMeters` étaient lus, et que `filters`, `locale`,
   `page`, `pageSize`, `sort_by`, `sort_order` étaient ignorés. Est-ce
   toujours vrai ? En particulier : **`locale` est-il exploité** ? Le client
   l'envoie (`search_providers.dart:215`) — s'il est déjà pris en compte, la
   demande 3 se réduit à l'étendre aux erreurs HTTP et à `Accept-Language`.
7. **`SearchFilters` : quels champs honorez-vous ?** Le client sérialise
   `location_type`, `spots_available`, `rating_min`, `language`
   (`search_models.dart:347-359`) qu'aucun écran n'expose. Nous les retirerons
   du modèle s'ils ne sont pas lus — dites-le, cela nous évite de câbler des
   contrôles inutiles.

### Sur les nouveaux contrats

8. **Existe-t-il une entité pivot pour l'activité parcourable ?** La demande 1
   suppose qu'un `Activity` (référentiel) et un `UserActivity` (déclaration
   d'un utilisateur) permettent de joindre proprement les programmes — c'est ce
   que suggèrent `activityId` et `userActivityId` déjà présents dans vos DTO.
   Confirme la sémantique exacte des deux : **laquelle des deux est la carte
   affichée dans l'Explorer ?** Si un « Yoga » du référentiel est déclaré par
   quinze utilisateurs, l'Explorer doit-il afficher une carte ou quinze ?
   *(Le client en affiche aujourd'hui une par `activityId`, dédupliquée —
   `program_providers.dart:244-250` — mais ce choix découle du bricolage, pas
   d'une décision produit.)*
9. **Codes d'erreur stables : en existe-t-il déjà ?** Les corps d'erreur
   observés portent un `code` (`INTERNAL_ERROR`, `NOT_FOUND`,
   `VALIDATION_ERROR` — cf. `BACKEND_PROFILE_FIX.md`). Est-il présent sur
   **tous** les refus métier 400/403/409/422, avec une énumération documentée ?
   Si oui, la demande 3 point (c) est déjà faite et nous pouvons traduire
   nous-mêmes les cas connus.
10. **Localisation : d'où viendraient les traductions ?** `clarificationQuestion`
    et `suggestedAlternatives` semblent générés dynamiquement (LLM ou
    templates). Si c'est un LLM, la langue est un paramètre de prompt et non un
    fichier de messages — dis-nous quelle approche tu retiens, cela conditionne
    la latence et le coût, deux choses que nous devrons afficher à
    l'utilisateur (l'écran de chargement fait déjà défiler des messages sur
    2,2 s, `search_page.dart:543`).
11. **`emptyStateActions[].payload` : le contrat est-il figé ?** Le client
    documente les payloads observés en production (`search_models.dart:176-181` :
    `EXPAND_RADIUS → {radiusMeters}`, `CREATE_SLOT → {activityId}` parfois
    absent, `SET_ALERT → {activityId, lat, lng, radiusMeters}`,
    `SIMILAR_ACTIVITY → {activityId, name}`), mais aucune spec ne les décrit.
    Peux-tu les documenter dans l'OpenAPI ? Le parsing client est tolérant
    (`search_models.dart:195-212`), donc rien n'est cassé — mais nous codons
    contre une observation, pas contre un contrat.
12. **Ordre de déploiement.** Les demandes 1 et 5 changent ce que la carte
    affiche. Préviens-nous avant toute mise en production : c'est le seul point
    où l'ordre compte, et nous garderons un chemin de repli tant que les deux
    versions coexistent.
