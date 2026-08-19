# Backend MeetDo / Pair — structure, fonctionnalités et base de données

> État du dépôt au **18 août 2026**, branche `master` (dernier commit `4677d02`).
> Ce document décrit ce que le code fait *aujourd'hui*, pas ce qui est prévu.
> Schéma de base relevé sur l'instance PostgreSQL locale, **58 migrations Flyway appliquées** (dernière : `V59__user_activity_category_announcement.sql`).

---

## 1. Vue d'ensemble

| | |
|---|---|
| Nom Maven | `org.program:Pair` (`0.0.1-SNAPSHOT`) |
| Framework | Spring Boot **4.1.0** |
| Langage | Java (`java.version` = 17 dans le POM ; image de build et d'exécution en **Temurin 21**) |
| Base | PostgreSQL 16 + **PostGIS 3.4** + **pgvector** + `uuid-ossp` |
| Migrations | Flyway (`db/migration`, V1 → V59) |
| Temps réel | WebSocket / STOMP (SockJS en repli) |
| Sécurité | Spring Security stateless + JWT (JJWT 0.12.3), BCrypt (force 12) |
| Recherche | Plein texte PostgreSQL (`tsvector`) + embeddings locaux **DJL / ONNX Runtime** (modèle trilingue FR/EN/DE), plus une taxonomie déterministe |
| Push | Firebase Admin SDK (activable ; implémentation `NoOp` sinon) |
| E-mail | SMTP (`spring-boot-starter-mail`) en local, **Resend** via API en production |
| Docs API | springdoc-openapi (`/swagger-ui.html`, `/v3/api-docs`) |
| Volume | ~27 700 lignes de Java applicatif, ~16 000 lignes de tests, **179 endpoints HTTP**, **81 classes de test**, **33 tables** |

Le backend sert une application mobile/web de mise en relation autour d'activités
pratiquées **au même endroit et au même moment** : un utilisateur déclare ses activités,
publie des *programmes* dotés de *créneaux* géolocalisés, d'autres les rejoignent, se
parlent, confirment leur présence, laissent des souvenirs partagés (*recaps*) et gagnent
des badges de confiance.

---

## 2. Architecture du code

### 2.1 Découpage

Le code est organisé en **tranches verticales par domaine métier**, et non en couches
globales. Chaque paquet sous `org.program.pair.domain.<domaine>` contient ses entités JPA,
ses énumérations, son ou ses services, son ou ses contrôleurs, et un sous-paquet `dto`
(souvent des `record`).

```
src/main/java/org/program/pair/
├── PairApplication.java          point d'entrée
├── config/                       10 classes de configuration Spring
├── controller/                   HomeController, AdminSeedController
├── api/                          GdprController
├── repository/                   30 interfaces Spring Data (dépôt central, transverse)
├── seed/                         amorçage des données de référence et de démo
├── shared/                       socle transverse (sécurité, erreurs, i18n, e-mail, géo…)
└── domain/
    ├── activity/     alert/      attendance/   audit/     auth/
    ├── badge/        chat/       email/        gdpr/      indexation/
    ├── map/          media/      notification/ program/   progression/
    ├── recap/        recommendation/ report/   review/    search/
    ├── subscription/ trust/      user/         websocket/
```

Particularité : les **repositories sont regroupés** dans `org.program.pair.repository`
plutôt que dans chaque domaine — c'est le seul écart notable au découpage vertical.

### 2.2 Le socle transverse (`shared/`)

| Paquet | Contenu |
|---|---|
| `shared/security` | `JwtAuthFilter` (extraction et validation du Bearer), `UserPrincipal`, `UserDetailsServiceImpl`, `RateLimiter` + `RateLimiterService` (Bucket4j) |
| `shared/exception` | `BusinessException` et sa famille (`ValidationException`, `ForbiddenException`, `ConflictException`, `ResourceNotFoundException`, `TooManyRequestsException`, `ScheduleConflictException`…), l'énumération `ErrorCode` (**50 codes**), et `GlobalExceptionHandler` qui les traduit en `ErrorResponse` JSON |
| `shared/i18n` | `Messages`, adossé aux fichiers `messages*.properties` |
| `shared/sanitizer` | `HtmlSanitizer` (OWASP Java HTML Sanitizer) appliqué aux contenus libres |
| `shared/logging` | `RequestIdFilter` : pose `X-Request-Id` dans le MDC, chaque ligne de log porte `[rid:…]`, l'en-tête est renvoyé au client |
| `shared/email` | `EmailService` |
| `shared/dto` | `ErrorResponse`, `ScheduleConflictResponse` |
| `shared/GeoUtils` | calculs géographiques (distances, bounding boxes) |

### 2.3 Configuration Spring (`config/`)

- **`SecurityConfig`** — chaîne stateless, CSRF désactivé, `JwtAuthFilter` avant
  `UsernamePasswordAuthenticationFilter`, `@EnableMethodSecurity`, `AuthenticationEntryPoint`
  personnalisé qui renvoie **401** (et non le 403 par défaut de Spring) en cas de token
  absent ou expiré. CORS explicite (origines Vercel + localhost, en-tête `X-Request-Id`
  autorisé **et** exposé).
- **`WebSocketConfig`** — broker simple sur `/topic` et `/queue`, préfixe applicatif
  `/app`, destinations utilisateur `/user`. Endpoint `/ws/chat` déclaré deux fois : avec
  et sans SockJS. Un `ChannelInterceptor` authentifie le STOMP `CONNECT` via le header
  `Authorization: Bearer …` et attache l'identifiant utilisateur à la session.
- **`JpaConfig`** — auditing JPA.
- **`AsyncConfig`** — pool `indexationExecutor` (2 → 5 threads) pour l'indexation asynchrone.
- **`LocaleConfig`** — trois locales supportées (**fr** par défaut, **en**, **de**),
  résolution par `Accept-Language` avec repli sur la plus proche supportée.
- **`FirebaseConfig`** — initialisation conditionnelle (`firebase.enabled`), credentials
  par fichier **ou** par chaîne base64.
- **`StorageConfig`**, **`WebClientConfig`**, **`OpenApiConfig`**, **`HttpConnectorConfig`**
  (connecteur HTTP additionnel sur `server.http.port`, en plus du port HTTPS principal).

### 2.4 Communication interne

Trois mécanismes coexistent :

1. **Appel direct de service** — le cas courant.
2. **Événements Spring** — `MessageSentEvent` → `ChatPushListener` (push d'un message),
   `UnreadChangedEvent` → synchronisation du badge d'icône, `BadgeSyncListener`,
   `ProgramIndexationListener` / `ActivityIndexationListener` (réindexation après écriture).
3. **`@Async`** — l'indexation (`IndexationService`) et l'envoi de push sortent du chemin
   de la requête.

---

## 3. Sécurité et confidentialité

### 3.1 Authentification

JWT en deux jetons : **access** (15 min) et **refresh** (30 jours), signés HS256 avec
`jwt.secret`. `AuthController` expose `register`, `login`, `refresh`, `verify-email`,
`forgot-password`, `reset-password`, `logout`.

### 3.2 Routes publiques

Tout est authentifié **sauf** : `/`, les six routes d'authentification listées ci-dessus,
`/ws/**`, Swagger, `/actuator/health`, et en lecture seule
`GET /api/categories`, `GET /api/activities`, `GET /api/map/activities`,
`GET /api/badges`, `GET /api/badges/users/**`, `GET /api/recommendations/users/**`,
`GET /api/recommendations/stats/**`, `GET /api/reviews/programs/**`.

### 3.3 Limitation de débit (Bucket4j, en mémoire)

| Action | Quota |
|---|---|
| Connexion | 10 / 15 min par IP |
| Inscription | 5 / heure par IP |
| Recherche | 30 / min par utilisateur |
| Upload média | 20 / heure par utilisateur |

Dépassement → `TooManyRequestsException` → HTTP 429 avec le code `RATE_LIMITED`.

### 3.4 Confidentialité par conception

- **Position utilisateur floutée** : `users.blur_radius_m`, plus les drapeaux
  `location_public`, `show_on_map`, `online_status_visible`, `show_age`, `show_last_active`.
- **Visibilité du profil** : `profile_visibility` (`PUBLIC` / `FRIENDS` / `PRIVATE`),
  `allow_messages` (`EVERYONE` / `FRIENDS` / `NONE`), `allow_subscriptions`
  (`OPEN` / `NOBODY`).
- **Adresse d'un créneau** : `SlotAddressVisibility` ne révèle les coordonnées exactes que
  si le lieu est public, ou si l'organisateur l'a explicitement autorisé, ou si le
  demandeur est un participant **confirmé**. Un créneau en ligne ne révèle jamais rien.
- **Recap** : consentement d'identité par participant (`recap_participant_consents`),
  visibilité `PRIVATE` / `PARTICIPANTS` / `PUBLIC`.
- **Statistiques de pratique** : `PracticeStatsService` porte une interdiction explicite
  en commentaire — aucune route de classement ne doit s'en servir pour trier les
  utilisateurs entre eux.

### 3.5 Erreurs

`GlobalExceptionHandler` normalise toute erreur en `{code, message, timestamp}`. Les 50
valeurs de `ErrorCode` couvrent le générique (`VALIDATION_ERROR`, `NOT_FOUND`,
`FORBIDDEN`, `CONFLICT`, `RATE_LIMITED`…) et le métier fin :
`SLOT_FULL`, `SLOT_ALREADY_JOINED`, `SCHEDULE_CONFLICT`, `PROGRAM_MESSAGES_DISABLED`,
`PROGRAM_BROADCAST_READ_ONLY`, `RECAP_WINDOW_CLOSED`, `RECAP_NOT_ATTENDEE`,
`ALREADY_SUBSCRIBED`, `MAP_BOUNDS_INVALID`, etc.

---

## 4. Fonctionnalités par domaine

### 4.1 Utilisateurs (`domain/user`)

Profil, avatar, position, mot de passe, confidentialité, statistiques de pratique.

```
GET    /api/users                       recherche/liste
GET    /api/users/me
PUT    /api/users/me
DELETE /api/users/me
GET    /api/users/{id}
GET    /api/users/me/practice-stats     ·  GET /api/users/{userId}/practice-stats
PUT    /api/users/me/location
POST   /api/users/me/avatar             ·  DELETE /api/users/me/avatar
POST   /api/users/me/change-password
GET    /api/users/{userId}/programs
GET    /api/users/me/privacy            ·  PUT /api/users/me/privacy
```

Compteurs dénormalisés recalculés après chaque confirmation de présence :
`distinct_partners_count`, `attendance_count`, `current_streak_weeks`, `last_attendance_at`.

### 4.2 Activités et catégories (`domain/activity`)

Catalogue de référence hiérarchique (`activities.parent_id`), catégories avec rampe de
couleur, et le lien `user_activities` qui dit *qui pratique quoi*, avec niveau
(`BEGINNER`…`EXPERT`, `ANY`) et format (`SOLO`, `DUO`, `GROUP`, `ANY`).

`ActivityBrowseService` (« l'Explorer ») remplace une jointure autrefois faite côté client
qui indexait les programmes par *nom d'activité normalisé* — deux « Yoga » de deux
organisateurs fusionnaient, « Yôga » et « Yoga » se séparaient. La clé est désormais la
vraie clé étrangère.

```
GET    /api/activities/browse
GET    /api/categories                  ·  POST /api/categories
GET    /api/activities                  ·  POST /api/activities
GET    /api/users/me/activities         ·  GET /api/users/{id}/activities
POST   /api/users/me/activities
PUT    /api/users/me/activities/{id}    ·  DELETE /api/users/me/activities/{id}
PATCH  /api/users/me/activities/{id}/visibility
PATCH  /api/activities/{id}/icon        ·  POST /api/activities/{id}/icon/upload
DELETE /api/activities/{id}/icon
```

### 4.3 Programmes et créneaux (`domain/program`)

Un **programme** appartient à une `user_activity` (donc à un utilisateur *et* une
activité). Il porte titre, description, objectifs, prérequis, durée en semaines, nombre de
séances par semaine, jours préférés (`integer[]`), moment préféré, capacité, modalité
(`REMOTE`/`ONLINE`/`IN_PERSON`/`HYBRID`), confidentialité, image, et le drapeau
`allow_participant_messages`.

Un **créneau** (`schedules`) est une occurrence géolocalisée : lieu (`PUBLIC`/`PRIVATE`/
`ONLINE`), point PostGIS, ville, adresse publique, début/fin, **règle de récurrence
RFC 5545**, capacité, statut (`OPEN`/`FULL`/`CANCELLED`/`PAST`), compteur de participants,
mot d'accueil.

Trois mécaniques valent d'être signalées :

- **`RecurrenceExpander`** (ical4j) interprète réellement `recurrence_rule` : sans lui, un
  créneau hebdomadaire gardait le `startsAt` de sa première séance et se déclarait
  terminé pour toujours.
- **`RecurringSlotRolloverJob`** (toutes les 10 min) fait avancer les créneaux récurrents
  passés vers leur prochaine occurrence — sans quoi le fil `/api/slots/feed` finissait par
  se vider entièrement.
- **`ScheduleConflictDetector`** refuse côté serveur qu'un utilisateur rejoigne un créneau
  chevauchant un créneau déjà rejoint. La vérification côté client existe aussi, mais deux
  appareils inscrits en parallèle la contourneraient.

```
POST   /api/programs                    ·  GET /api/programs   ·  GET /api/programs/new
GET    /api/programs/{id}               ·  PUT/PATCH/DELETE /api/programs/{id}
POST   /api/programs/{id}/duplicate
POST   /api/programs/{id}/image/upload  ·  DELETE /api/programs/{id}/image
POST   /api/programs/{id}/schedules     ·  PUT/DELETE /api/programs/{id}/schedules/{sid}
POST   /api/programs/{id}/report

POST   /api/programs/{id}/join          ·  POST /api/programs/{id}/leave
GET    /api/programs/{id}/participants/count
GET    /api/programs/{id}/enrollment-status
GET    /api/users/me/programs           ·  PATCH/DELETE /api/users/me/programs/{upid}
POST   /api/users/me/programs/{upid}/activities/{aid}/complete
POST   /api/users/me/programs/{upid}/activities/{aid}/skip

GET    /api/slots/feed                  ·  GET /api/slots/mine
GET    /api/slots/{sid}                 ·  GET /api/slots/{sid}/participants
POST   /api/slots/{sid}/join            ·  DELETE /api/slots/{sid}/join
```

### 4.4 Présence et statistiques (`domain/attendance`)

Après un créneau, chaque participant confirme sa présence. La confirmation débloque la
recommandation entre pairs (preuve d'interaction `SHARED_ATTENDANCE`), alimente le recap
et met à jour les compteurs de pratique.

```
POST   /api/attendances/{scheduleId}/confirm
GET    /api/attendances/pending
GET    /api/attendances/{scheduleId}/co-participants
```

`AttendancePromptJob` relance **une seule fois**, pour les créneaux terminés entre 1 h et
3 h auparavant — règle produit assumée : jamais de rappel insistant.

### 4.5 Recaps de créneau (`domain/recap`)

Un souvenir partagé, créé par occurrence (`uq_recap_occurrence(schedule_id, occurrence_start)`).

Règles codées : fenêtre de contribution de **7 jours** après la fin de l'occurrence,
**2 vibes maximum** par utilisateur parmi 8 (`RELAXED`, `ENERGETIC`, `FRIENDLY`,
`TECHNICAL`, `BEGINNER_FRIENDLY`, `GOOD_LAUGH`, `FOCUSED`, `OUTDOORS`), **3 vibes**
affichées au sommet, **3 photos** maximum, note de l'organisateur limitée à 400 caractères.

```
GET    /api/slots/{sid}/recap
POST   /api/slots/{sid}/recap/vibes     ·  DELETE /api/slots/{sid}/recap/vibes
PATCH  /api/slots/{sid}/recap/consent   ·  PATCH …/photo  ·  PATCH …/note  ·  PATCH …/visibility
GET    /api/recaps/feed                 ·  GET /api/recaps/mine
GET    /api/programs/{id}/recaps  ·  /api/activities/{id}/recaps  ·  /api/users/{id}/recaps
```

### 4.6 Messagerie (`domain/chat`)

Conversations `DIRECT`, `GROUP` ou `PROGRAM_BROADCAST`, avec contexte optionnel
(activité, programme, créneau). Messages éditables (historique conservé dans
`message_edit_history`), supprimables en douceur (`deleted_at`), avec images.

La **diffusion programme** n'ajoute qu'une seule route : le fil créé apparaît ensuite dans
la messagerie normale avec `type: "PROGRAM_BROADCAST"`, et `POST /api/conversations/{id}/messages`
refuse en 403 tout expéditeur autre que l'auteur du programme.

```
POST   /api/conversations               ·  GET /api/conversations
GET    /api/conversations/unread-count
GET    /api/conversations/{id}          ·  DELETE /api/conversations/{id}
POST   /api/conversations/{id}/messages ·  GET /api/conversations/{id}/messages
POST   /api/conversations/{id}/read     ·  POST /api/conversations/{id}/read-all
POST   /api/conversations/{id}/images
PATCH  /api/messages/{id}               ·  DELETE /api/messages/{id}
POST   /api/programs/{id}/broadcasts
STOMP  /app/chat.send
```

### 4.7 Notifications et push (`domain/notification`)

**30 types** de notification (`NEW_MESSAGE`, `PROGRAM_REMINDER`, `SLOT_JOINED`,
`ATTENDANCE_PROMPT`, `BADGE_EARNED`, `AUTHOR_NEW_PROGRAM`, `CATEGORY_NEW_ACTIVITY`,
`ACTIVITY_ALERT_MATCH`, `STREAK_MILESTONE`…), trois canaux (`EMAIL`, `PUSH`, `IN_APP`),
trois fréquences (`IMMEDIATE`, `DAILY_DIGEST`, `WEEKLY`), préférences par type et par
utilisateur.

Le push Firebase est **localisé par appareil** : `device_tokens` porte `locale` et
`timezone`, et `PushNotificationService` regroupe les envois par (locale, variante,
fuseau) avant de composer titre et corps depuis les `messages*.properties`. Les tokens
invalides sont purgés à la réponse de Firebase.

`UnreadCounter` centralise le total non lu : iOS n'offre **qu'un** badge d'icône, un
message non lu y compte donc autant qu'une notification non lue.

```
GET    /api/notifications               ·  GET /api/notifications/unread-count
PUT    /api/notifications/{id}/read     ·  PUT /api/notifications/read-all
DELETE /api/notifications/{id}
GET    /api/notifications/preferences   ·  PUT /api/notifications/preferences
POST   /api/notifications/devices       ·  GET /api/notifications/devices
DELETE /api/notifications/devices/{token}
```

### 4.8 Abonnements (`domain/subscription`)

Trois cibles : un **auteur**, une **user_activity**, une **catégorie**. Trois niveaux
(`ALL`, `NEW_ONLY`, `MUTED`). Une portée géographique optionnelle (`lat`/`lng`/`radius_meters`)
pour les abonnements de catégorie. Unicité garantie par trois index partiels, un par type.

Le service pousse les notifications de fan-out : nouvelle activité d'un auteur suivi,
nouveau programme, mise à jour d'activité, et — cas particulier — **première annonce
localisée d'une catégorie** (`user_activities.category_notified_at` évite le doublon).

```
POST/DELETE/PATCH  /api/users/{id}/subscription
POST/DELETE/PATCH  /api/user-activities/{id}/subscription
POST/DELETE/PATCH  /api/categories/{id}/subscription
GET                /api/users/me/subscriptions   ·  GET /api/users/me/subscribers
```

### 4.9 Recherche (`domain/search`)

Trois couches complémentaires :

1. **`FullTextSearchService`** — `tsvector` PostgreSQL sur `programs.search_vector`
   (index GIN), entretenu de façon asynchrone par `IndexationService`.
2. **`SemanticSearchService`** — embeddings vectoriels avec index **HNSW cosinus**
   (`m=16, ef_construction=64`) sur `activities`, `programs` et `search_logs`. Le modèle
   tourne **en local** (DJL + ONNX Runtime + tokenizers HuggingFace), téléchargé au premier
   démarrage : plus aucun appel à une API payante. Seuil de similarité configurable,
   défaut **0,25**, volontairement bas pour tolérer l'écart du matching interlingue.
3. **`ActivityTaxonomy`** — table de correspondance canonique EN/DE/FR garantissant le
   matching déterministe sur les activités connues (« Laufen » → slug `running` → tous les
   programmes de course, quelle que soit la langue de stockage).

`RuleBasedIntentExtractor` remplace l'appel LLM d'origine par un pipeline de règles et
mots-clés FR/EN/DE : il ne comprend pas le langage naturel libre, mais il est gratuit,
local et ne lève jamais d'exception. `TimeHintParser` traduit « demain soir », « ce
week-end » en fenêtre `[from, to]`, avec repli sur *maintenant → +7 jours*.

```
POST   /api/search
GET    /api/search/popular              ·  GET /api/search/recent
DELETE /api/search/recent               ·  DELETE /api/search/recent/{id}
GET    /api/search/tags                 ·  GET /api/search/tags/popular
```

### 4.10 Carte (`domain/map`)

Utilisateurs, activités et programmes affichés par emprise (*bounds*), par rayon, ou
agrégés en **clusters** dont la taille de grille dépend du zoom. Le service applique le
floutage de position et les drapeaux de confidentialité avant de rendre quoi que ce soit.
Géocodage et géocodage inverse inclus.

```
GET    /api/map/users      ·  /api/map/clusters  ·  /api/map/bounds
GET    /api/map/nearby/{type}  ·  /api/map/activities
GET    /api/map/geocode    ·  /api/map/reverse-geocode
POST   /api/map/location
```

### 4.11 Alertes de proximité (`domain/alert`)

Un utilisateur pose une alerte « préviens-moi si une séance de *X* apparaît dans *N* mètres
autour d'ici ». Point PostGIS + rayon, index GIST, une alerte par (utilisateur, activité),
horodatage du dernier déclenchement.

```
GET/POST  /api/alerts   ·  PATCH/DELETE /api/alerts/{id}
```

### 4.12 Confiance : avis, recommandations, badges (`domain/review`, `recommendation`, `badge`, `trust`)

- **Avis** sur un programme : un par (programme, relecteur), score global plus sous-scores
  par critère (`review_criteria`), et une **preuve d'interaction** obligatoire.
- **Recommandations entre pairs** : une par (recommandeur, recommandé), avec preuve
  `CONVERSATION` **ou** `SHARED_ATTENDANCE` (double confirmation de présence sur le même
  créneau — une preuve au moins aussi forte qu'une conversation).
- **Badges** : évaluation automatique par `BadgeConditionType` (21 conditions —
  `ATTENDANCE_COUNT`, `DISTINCT_PARTNERS`, `WEEKLY_STREAK`, `PROGRAM_COUNT`,
  `ACTIVITY_DIVERSITY`, `RECOMMENDATION_COUNT`, `SLOT_HOSTED_COUNT`…). Les badges `MANUAL`
  ne peuvent jamais être attribués automatiquement.

```
GET    /api/reviews/programs/{id}  ·  /api/reviews/programs/{id}/summary
POST   /api/reviews  ·  GET /api/reviews/me  ·  GET /api/reviews/can-review/{programId}
POST   /api/recommendations  ·  GET /api/recommendations/received | given
GET    /api/recommendations/users/{id} | stats/{id} | can-recommend/{id} | me/stats
GET    /api/badges  ·  /api/badges/me  ·  /api/badges/users/{id}  ·  /api/badges/me/count
POST   /api/badges/me/evaluate
```

### 4.13 Progression (`domain/progression`)

Journal de progression d'un participant sur un programme (titre, contenu, métriques
libellées, public ou privé), avec séries (*streaks*) et statistiques.

```
POST/GET/PUT/DELETE  /api/progressions[/{id}]
GET    /api/progressions/program/{id}  ·  /user/{id}  ·  /my  ·  /my/streak  ·  /my/stats
```

### 4.14 Médias (`domain/media`)

Stockage local sur disque (`storage.location`, volume monté en production).
`MediaValidator` contrôle le type réel via **Apache Tika**, `ImageProcessor` redimensionne,
ré-encode (par sécurité) et compresse via **Thumbnailator**. Limite : 10 Mo par fichier.

`StoredImageResolver` vérifie l'existence du fichier avant de rendre l'URL et retourne
`null` s'il a disparu — séquelle de l'incident du 11 août 2026 qui a laissé en base des
références orphelines.

```
POST   /api/media/upload/image  ·  POST /api/media/upload/avatar
GET    /api/media/files/{*path} ·  DELETE /api/media/files/{*path}
```

### 4.15 Signalements et modération (`domain/report`)

Signalement d'un `USER`, `PROGRAM` ou `MESSAGE`, sept motifs (`SPAM`, `HARASSMENT`,
`INAPPROPRIATE_CONTENT`, `FAKE_PROFILE`, `VIOLENCE`, `HATE_SPEECH`, `OTHER`), cycle
`PENDING` → `REVIEWED` / `ACTIONED` / `DISMISSED`.

```
POST   /api/reports  ·  GET /api/reports/me  ·  GET /api/reports/pending
PUT    /api/reports/{id}/review
```

### 4.16 RGPD et audit (`api/GdprController`, `domain/gdpr`, `domain/audit`)

Export complet des données d'un utilisateur, suppression de compte avec anonymisation, et
`audit_logs` traçant chaque action sensible (acteur, type, entité, ancienne et nouvelle
valeur, IP, user-agent).

```
GET    /api/gdpr/export  ·  DELETE /api/gdpr/delete-account
```

### 4.17 Administration et indexation

```
POST   /api/admin/seed/demo/reset  ·  POST /api/admin/seed/status
GET    /api/indexation/stats
POST   /api/indexation/reindex/programs | reindex/activities | reindex/all
POST   /api/indexation/backfill-embeddings
```

---

## 5. Traitements planifiés

| Job | Cadence | Rôle |
|---|---|---|
| `RecurringSlotRolloverJob` | `0 */10 * * * *` | avance les créneaux récurrents passés vers l'occurrence suivante |
| `ProgramReminderJob` | `0 */5 * * * *` | rappel « votre séance commence bientôt » à **T‑2 h** |
| `AttendancePromptJob.promptAttendanceConfirmation` | horaire (`:00`) | invite à confirmer la présence, une seule fois, pour les créneaux terminés depuis 1 à 3 h |
| `AttendancePromptJob.closeElapsedSlots` | horaire (`:15`) | ferme les créneaux écoulés |
| `GdprPurgeJob.purgeInactiveAccounts` | quotidien 03 h 00 | purge des comptes inactifs |
| `GdprPurgeJob.purgeOldAuditLogs` | mensuel, le 1er à 04 h 00 | purge des journaux d'audit anciens |

---

## 6. Base de données

### 6.1 Extensions

`uuid-ossp` (identifiants), **PostGIS** (colonnes `geometry`, index GIST),
**pgvector** (colonnes `vector`, index HNSW cosinus).

### 6.2 Inventaire des tables (33 tables applicatives)

| Domaine | Tables |
|---|---|
| Identité | `users`, `audit_logs` |
| Catalogue | `categories`, `activities`, `user_activities` |
| Programmes | `programs`, `schedules`, `program_media`, `user_programs`, `program_activities` |
| Créneaux | `slot_participations`, `attendances` |
| Recaps | `slot_recaps`, `recap_vibe_votes`, `recap_participant_consents` |
| Messagerie | `conversations`, `conversation_members`, `messages`, `message_edit_history` |
| Confiance | `reviews`, `review_criteria`, `peer_recommendations`, `badges`, `badge_awards` |
| Notifications | `notifications`, `notification_prefs`, `device_tokens` |
| Abonnements | `subscriptions` |
| Alertes | `activity_alerts` |
| Progression | `progressions`, `progression_entries` |
| Recherche | `search_logs` |
| Modération | `reports` |

> **Doublon connu** : `progressions` et `progression_entries` coexistent ; seule
> `progressions` est mappée par l'entité `Progression` (elle ajoute `metric_labels` et
> `updated_at`). `progression_entries` est un vestige de `V9`.

### 6.3 Colonnes, table par table

*(`NN` = NOT NULL)*

**`users`** — `id uuid NN`, `email varchar(255) NN` **unique**, `password_hash varchar(255) NN`,
`phone varchar(20)`, `display_name varchar(80) NN`, `bio varchar(1000)`,
`avatar_url varchar(500)`, `location geometry`, `blur_radius_m int NN`,
`location_public bool NN`, `online_status_visible bool NN`, `receive_messages bool NN`,
`verification_status varchar(30) NN`, `verified_at`, `created_at NN`, `last_active_at`,
`is_active bool NN`, `profile_visibility varchar(20)`, `show_age`, `show_last_active`,
`show_location`, `allow_messages varchar(20)`, `show_on_map`,
`distinct_partners_count int NN`, `attendance_count int NN`, `current_streak_weeks int NN`,
`last_attendance_at`, `allow_subscriptions varchar(20) NN`.

**`categories`** — `id NN`, `name varchar(80) NN` **unique**, `icon varchar(80)`, `color_ramp varchar(30) NN`.

**`activities`** — `id NN`, `parent_id`, `category_id NN`, `name varchar(120) NN`,
`slug varchar(150) NN` **unique**, `description varchar(500)`, `embedding vector`,
`created_at NN`, `icon varchar(80) NN`, `image_url varchar(500)`.

**`user_activities`** — `id NN`, `user_id NN`, `activity_id NN`, `visible_on_map bool NN`,
`custom_description varchar(500)`, `level varchar(20)`, `format varchar(10)`,
`created_at NN`, `category_notified_at`. Unique `(user_id, activity_id)`.

**`programs`** — `id NN`, `user_activity_id NN`, `title varchar(150) NN`, `description text`,
`embedding vector`, `status varchar(20) NN`, `is_public bool NN`, `archived_at`,
`created_at NN`, `updated_at`, `search_vector tsvector`, `organizer_name varchar(80)`,
`organizer_avatar_url varchar(500)`, `next_session_at`, `duration_weeks int`,
`sessions_per_week int`, `session_duration_minutes int`, `preferred_days int[]`,
`preferred_time varchar(20)`, `max_participants int`, `privacy varchar(20)`, `goals text`,
`prerequisites text`, `location_type varchar(20)`, `image_url varchar(500)`,
`allow_participant_messages bool NN`, `subscribers_notified_at`.

**`schedules`** — `id NN`, `program_id NN`, `place_name varchar(200) NN`,
`place_type varchar(10) NN`, `location geometry NN`, `address_public varchar(300)`,
`show_exact_address bool NN`, `starts_at NN`, `ends_at`, `recurrence_rule varchar(200)`,
`max_participants int`, `created_at NN`, `is_open_to_partners bool NN`,
`status varchar(20) NN`, `participant_count int NN`, `welcome_note varchar(300)`,
`reminder_sent_for`, `city varchar(120)`, `last_occurrence_start`, `last_occurrence_end`.

**`program_media`** — `id NN`, `program_id NN`, `url varchar(500) NN`,
`media_type varchar(10) NN`, `sort_order int NN`, `created_at NN`.

**`user_programs`** — `id NN`, `user_id NN`, `program_id NN`, `schedule_id`,
`status varchar(20) NN`, `leave_reason text`, `progress_percentage int NN`,
`activities_completed int NN`, `activities_skipped int NN`, `last_activity_at`,
`joined_at NN`, `left_at`. Unique `(user_id, program_id, status)`.

**`program_activities`** — `id NN`, `user_program_id NN`, `activity_id NN`,
`status varchar(20) NN`, `completed_at`, `skipped_at`, `notes text`.
Unique `(user_program_id, activity_id)`.

**`slot_participations`** — `id NN`, `schedule_id NN`, `user_id NN`, `status varchar(20) NN`,
`join_message varchar(300)`, `created_at NN`. Unique `(schedule_id, user_id)`.

**`attendances`** — `id NN`, `schedule_id NN`, `user_id NN`, `was_present bool NN`,
`attended_at NN`, `confirmed_at NN`, `memory_photo_url varchar(500)`,
`memory_is_public bool NN`. Unique `(schedule_id, user_id, attended_at)`.

**`slot_recaps`** — `id NN`, `schedule_id NN`, `visibility varchar(20) NN`,
`host_note varchar(400)`, `attendee_count int NN`, `published_at`, `created_at NN`,
`updated_at NN`, `occurrence_start NN`, `occurrence_end NN`.
Unique `(schedule_id, occurrence_start)`.

**`recap_vibe_votes`** — `id NN`, `recap_id NN`, `user_id NN`, `vibe varchar(30) NN`,
`created_at NN`. Unique `(recap_id, user_id, vibe)`.

**`recap_participant_consents`** — `recap_id NN`, `user_id NN`, `show_identity bool NN`,
`created_at NN` (clé composite).

**`conversations`** — `id NN`, `type varchar(30) NN`, `activity_context_id`, `created_at NN`,
`last_message_at`, `program_id`, `schedule_id`. Index unique **partiel** sur `program_id`
là où `type = 'PROGRAM_BROADCAST'` : un seul fil de diffusion par programme.

**`conversation_members`** — `conversation_id NN`, `user_id NN`, `joined_at NN`,
`last_read_at` (clé composite).

**`messages`** — `id NN`, `conversation_id NN`, `sender_id NN`, `content varchar(4000) NN`,
`status varchar(15) NN`, `sent_at NN`, `read_at`, `edited_at`, `deleted_at`,
`image_url varchar(500)`.

**`message_edit_history`** — `id NN`, `message_id NN`, `previous_content varchar(4000) NN`, `edited_at NN`.

**`reviews`** — `id NN`, `program_id NN`, `reviewer_id NN`, `interaction_proof_id`,
`score float8 NN`, `comment varchar(1000)`, `created_at NN`, `overall_rating int`,
`criteria_scores jsonb`, `conversation_id`, `updated_at`, `interaction_proof_type varchar(20)`.
Unique `(program_id, reviewer_id)`.

**`review_criteria`** — `id NN`, `review_id NN`, `criterion_key varchar(30) NN`, `score float8 NN`.

**`peer_recommendations`** — `id NN`, `recommender_id NN`, `recommended_id NN`,
`conversation_id`, `comment varchar(500)`, `created_at NN`, `rating int`,
`activity_context`, `program_context`, `updated_at`, `interaction_proof_type varchar(20)`.
Unique `(recommender_id, recommended_id)`.

**`badges`** — `id NN`, `code varchar(60) NN` **unique**, `category varchar(20) NN`,
`label varchar(120) NN`, `condition_type varchar(40) NN`, `condition_threshold int`, `icon varchar(80)`.

**`badge_awards`** — `badge_id NN`, `user_id NN`, `awarded_at NN` (clé composite).

**`notifications`** — `id NN`, `user_id NN`, `type varchar(40) NN`, `channel varchar(10) NN`,
`payload jsonb`, `is_read bool NN`, `sent_at NN`, `read_at`.

**`notification_prefs`** — `id NN`, `user_id NN`, `notification_type varchar(40) NN`,
`email_enabled bool NN`, `push_enabled bool NN`, `frequency varchar(20) NN`.
Unique `(user_id, notification_type)`.

**`device_tokens`** — `id NN`, `user_id NN`, `token varchar(500) NN` **unique**,
`platform varchar(20) NN`, `device_name varchar(100)`, `created_at NN`, `last_used_at NN`,
`locale varchar(10)`, `timezone varchar(64)`.

**`subscriptions`** — `id NN`, `subscriber_id NN`, `type varchar(20) NN`, `target_author_id`,
`target_user_activity_id`, `target_category_id`, `created_at NN`, `level varchar(20) NN`,
`lat float8`, `lng float8`, `radius_meters int`.

**`activity_alerts`** — `id NN`, `user_id NN`, `activity_id NN`, `location geometry NN`,
`radius_meters int NN`, `is_active bool NN`, `last_triggered_at`, `created_at NN`.
Unique `(user_id, activity_id)`.

**`progressions`** — `id NN`, `program_id NN`, `user_id NN`, `title varchar(150)`,
`content text`, `metrics []`, `metric_labels []`, `is_public bool NN`, `created_at NN`, `updated_at`.

**`search_logs`** — `id NN`, `user_id`, `raw_query varchar(500) NN`, `parsed_intent text`,
`query_embedding vector`, `results_count int`, `searched_at NN`, `search_method varchar(50)`.

**`reports`** — `id NN`, `reporter_id NN`, `reported_entity_type varchar(20) NN`,
`reported_entity_id NN`, `reason varchar(30) NN`, `status varchar(20) NN`, `created_at NN`,
`resolved_at`, `description varchar(500)`, `reviewed_by`, `reviewed_at`,
`resolution_notes text`, `updated_at`.

**`audit_logs`** — `id NN`, `user_id`, `action_type varchar(50) NN`,
`entity_type varchar(50) NN`, `entity_id`, `old_value text`, `new_value text`,
`ip_address varchar(45)`, `user_agent varchar(255)`, `created_at timestamp NN`
*(seule colonne temporelle sans fuseau de tout le schéma)*.

### 6.4 Politique des clés étrangères

La règle générale est `ON DELETE CASCADE` : supprimer un utilisateur emporte ses activités,
programmes, messages, abonnements, appareils, alertes, présences et badges.

Trois exceptions délibérées :

| Contrainte | Politique | Raison |
|---|---|---|
| `activities.category_id → categories.id` | **RESTRICT** | on ne supprime pas une catégorie encore utilisée |
| `reviews.interaction_proof_id → conversations.id` | **RESTRICT** | la preuve d'interaction ne doit pas disparaître sous un avis |
| `peer_recommendations.conversation_id → conversations.id` | **RESTRICT** | idem pour une recommandation |

Six `SET NULL`, où la référence n'est qu'un contexte optionnel :
`user_programs.schedule_id`, `conversations.program_id` / `schedule_id` /
`activity_context_id`, `search_logs.user_id`, `audit_logs.user_id`.

Et trois FK **sans action** (`NO ACTION`), qui bloquent donc la suppression :
`attendances.user_id`, `slot_participations.user_id`, `activity_alerts.activity_id`.

### 6.5 Index remarquables

**Spatiaux (GIST)** — `users(location)`, `schedules(location)`, `activity_alerts(location)`.

**Vectoriels (HNSW, cosinus, `m=16 / ef_construction=64`)** —
`activities(embedding)`, `programs(embedding)`, `search_logs(query_embedding)`,
tous en **`vector(384)`** depuis le passage au modèle local (`V48`).

**Plein texte (GIN)** — `programs(search_vector)`.

**Partiels** — trois index d'unicité sur `subscriptions`, un par type d'abonnement ;
`uq_conversations_program_broadcast` sur `conversations(program_id) WHERE type = 'PROGRAM_BROADCAST'` ;
`idx_schedules_reminder_sweep` sur `schedules(starts_at) WHERE status IN ('OPEN','FULL')`,
taillé pour le balayage du job de rappel.

### 6.6 Historique des migrations

58 fichiers appliqués. Trois périodes se lisent dans la numérotation :

- **V1 → V15** — mise en place du schéma (extensions, utilisateurs, catalogue, programmes,
  messagerie, confiance, notifications, modération, audit).
- **V16 → V39** — corrections et alignements : décalages entité/schéma (`V18`), colonnes de
  compatibilité (`V19`), et une série peu flatteuse de **six migrations successives**
  (`V29` → `V34`) pour réparer des hachages de mots de passe de données de démo.
- **V40 → V59** — les fonctionnalités récentes : créneaux et participations (`V40`),
  présences (`V41`), alertes (`V42`), abonnements (`V36`, `V58`), embeddings locaux (`V48`),
  contexte programme des conversations (`V51`), diffusion programme (`V53`), recaps
  (`V54`, `V57`), localisation des appareils (`V49`, `V56`).

Configuration Flyway notable : `baseline-on-migrate=true`, `validate-on-migrate=false` et
`repair-on-migrate=true` — tolérant, au prix d'une détection plus faible des dérives.

### 6.7 Amorçage des données

- **`ReferenceDataSeeder`** (`pair.seed.reference-data.enabled`, actif par défaut) charge
  catégories, activités et badges depuis `resources/seed/data/*.json`, en ignorant ce qui
  existe déjà, puis génère les embeddings manquants.
- **`DemoDataSeeder`** (`pair.seed.demo-data.enabled`, désactivé en local, **activé** sur
  Railway) et `ResetDemoDataCommand`, pilotable par `POST /api/admin/seed/demo/reset`.

---

## 7. Internationalisation

Trois langues : **français** (défaut et repli), **anglais**, **allemand** —
`messages.properties` (127 lignes), `messages_en.properties` (114),
`messages_de.properties` (116). La résolution se fait sur `Accept-Language`, avec
rapprochement vers la locale supportée la plus proche. Le push utilise en priorité la
`locale` enregistrée sur l'appareil.

> Les fichiers `en` et `de` comptent une dizaine de clés de moins que le français : des
> traductions manquent.

---

## 8. Tests

**81 classes de test**, ~16 000 lignes.

- **Tests unitaires** — un par service dans `domain/<domaine>/`, sur Mockito.
- **Tests d'intégration** — `src/test/.../integration/`, adossés à
  `AbstractIntegrationTest` et **Testcontainers PostgreSQL** (image PostGIS), donc contre
  une vraie base avec les vraies migrations. Ils couvrent les parcours transverses :
  authentification, chat WebSocket, carte, recherche sémantique et multilingue,
  pagination, contrat OpenAPI, injections, RGPD, codes d'erreur métier, conflits d'agenda,
  recaps, abonnements, diffusion programme, service de fichiers médias.
- Un faux `com.google.firebase.messaging.FcmResponses` permet de tester le push sans
  Firebase réel.

---

## 9. Configuration et déploiement

### 9.1 Profils

| Profil | Particularités |
|---|---|
| *(défaut)* | PostgreSQL local `localhost:5432/pair_db`, **HTTPS sur 8090** (keystore PKCS12 embarqué) + HTTP sur 8091, seed de démo désactivé |
| `dev` | développement local |
| `staging` | pré-production |
| `prod` | production |
| `railway` | SSL désactivé (terminaison en amont), port `${PORT:8080}`, connexion via `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`, **Resend** à la place de SMTP, seed de démo **activé**, modèle d'embeddings sur volume persistant `/app/models/embedding` |

### 9.2 Variables d'environnement principales

`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `SMTP_*` / `RESEND_*`, `EMAIL_FROM`,
`APP_BASE_URL` / `FRONTEND_URL`, `STORAGE_PATH`, `FIREBASE_ENABLED` +
`FIREBASE_CREDENTIALS_PATH` ou `FIREBASE_CREDENTIALS_BASE64`, `REDIS_ENABLED`,
`MEETDO_EMBEDDING_ENABLED`, `MODEL_PATH`, `MODEL_BASE_URL`,
`SEARCH_EMBEDDING_MIN_SIMILARITY`.

### 9.3 Image Docker

Build multi-étapes : `eclipse-temurin:21-jdk-alpine` pour compiler (`mvnw clean package
-DskipTests`), `eclipse-temurin:21-jre-jammy` pour exécuter. `STORAGE_PATH=/app/uploads`
est écrit **en absolu** pour coïncider visiblement avec le point de montage du volume
Railway — précaution née de l'incident du 11 août 2026, où tous les médias de production
étaient devenus illisibles.

### 9.4 Supervision

Actuator expose `health`, `info`, `metrics`. `/actuator/health` est public ; les détails ne
sont visibles qu'aux appelants autorisés. Chaque ligne de journal porte le
`X-Request-Id` de la requête en cours, renvoyé au client dans la réponse.

---

## 10. Points d'attention

Relevés à la lecture du code, sans jugement sur leur priorité :

1. **Secrets par défaut en clair** dans `application.properties` : `jwt.secret` a une
   valeur de repli en dur, `server.ssl.key-store-password=pair2026` aussi, et
   `spring.datasource.password` retombe sur `Pair2026!`. Acceptable en local, à couvrir par
   les variables d'environnement partout ailleurs.
2. **`progressions` / `progression_entries`** — deux tables pour un seul concept, une seule
   mappée.
3. **Flyway permissif** — `validate-on-migrate=false` et `repair-on-migrate=true` masquent
   les dérives de checksum.
4. **Traductions incomplètes** en anglais et en allemand.
5. **Rate limiting en mémoire** — les quotas Bucket4j ne sont pas partagés entre instances ;
   Redis est déjà en dépendance mais désactivé par défaut.
6. **Racine du dépôt encombrée** — une dizaine de fichiers `.log`, des scripts de test et
   des documents de mise en production y sont versionnés.
7. **Six migrations consécutives** (`V29`–`V34`) pour un même correctif de hachage : le
   coût d'une correction de données passée par le mécanisme de migration.

---

## 11. Pour aller plus loin

| Sujet | Document |
|---|---|
| Schéma détaillé colonne par colonne | `docs/DATABASE_SCHEMA.md` |
| Contrats et échanges avec le client | `docs/specs/PROMPT_*.md`, `docs/specs/REPONSE_*.md` |
| Guide frontend | `docs/FRONTEND_SPEC.md`, `docs/FRONTEND_DATABASE_GUIDE.md` |
| Déploiement Railway | `RAILWAY_ENV_VARS.md`, `QUICK_START_RAILWAY.md`, `docs/deployment/` |
| Push Firebase | `docs/specs/meetdo-firebase-push-activation.md` |
| E-mail Resend | `RESEND_SETUP.md`, `RESEND_QUICKSTART.md` |
| API interactive | `/swagger-ui.html` sur une instance démarrée |
