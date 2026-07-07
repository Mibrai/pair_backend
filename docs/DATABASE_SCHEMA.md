# Schéma de la base de données — Pair

Extensions PostgreSQL activées : **uuid-ossp**, **PostGIS** (géolocalisation), **pgvector** (recherche sémantique).

---

## Vue d'ensemble — toutes les tables

| Table | Rôle |
|---|---|
| `users` | Comptes utilisateurs et paramètres de confidentialité |
| `categories` | Catalogue de catégories d'activités |
| `activities` | Catalogue de référence des activités sportives |
| `user_activities` | Activités pratiquées par un utilisateur |
| `programs` | Programmes sportifs créés par un utilisateur |
| `schedules` | Créneaux géolocalisés d'un programme |
| `program_media` | Photos/vidéos attachées à un programme |
| `user_programs` | Inscriptions d'un utilisateur à un programme |
| `program_activities` | Suivi d'activités dans une inscription |
| `conversations` | Fils de messagerie (directs ou contextuels) |
| `conversation_members` | Participants d'une conversation |
| `messages` | Messages individuels |
| `reviews` | Avis laissés sur un programme |
| `review_criteria` | Sous-scores d'un avis (ponctualité, pédagogie…) |
| `peer_recommendations` | Recommandations entre utilisateurs |
| `badges` | Définitions des badges de gamification |
| `badge_awards` | Badges obtenus par un utilisateur |
| `notifications` | Notifications envoyées |
| `notification_prefs` | Préférences de notification par type |
| `device_tokens` | Tokens push (Firebase) par appareil |
| `reports` | Signalements (utilisateur, message, programme) |
| `search_logs` | Historique des recherches pour analytics/LLM |
| `progression_entries` | Journal de progression d'un participant |
| `audit_logs` | Traçabilité RGPD de toutes les actions |

---

## Diagramme de dépendances simplifié

```
categories
    └── activities  ─────────────────────────────────────────┐
            └── user_activities                               │
                    └── programs                              │
                            ├── schedules                     │
                            ├── program_media                 │
                            ├── user_programs ── program_activities
                            ├── reviews ── review_criteria    │
                            └── progression_entries           │
                                                              │
users ──────────────────────────────────────────────────────>┘
    ├── user_activities
    ├── user_programs
    ├── conversations ── conversation_members
    │       └── messages
    ├── reviews / peer_recommendations
    ├── badge_awards ──── badges
    ├── notifications / notification_prefs
    ├── device_tokens
    ├── reports
    ├── search_logs
    ├── progression_entries
    └── audit_logs
```

---

## Détail par fonctionnalité

---

### 1. Gestion des utilisateurs

**Tables impliquées :** `users`

```
users
├── id                    UUID PK
├── email                 VARCHAR(255) UNIQUE NOT NULL
├── password_hash         VARCHAR(255) NOT NULL
├── phone                 VARCHAR(20)
├── display_name          VARCHAR(80) NOT NULL
├── bio                   VARCHAR(1000)
├── avatar_url            VARCHAR(500)
├── location              GEOMETRY(Point, 4326)   ← PostGIS
├── blur_radius_m         INTEGER DEFAULT 500      ← flou de position
├── location_public       BOOLEAN DEFAULT FALSE
├── online_status_visible BOOLEAN DEFAULT FALSE
├── receive_messages      BOOLEAN DEFAULT TRUE
├── verification_status   VARCHAR(30) DEFAULT 'UNVERIFIED'
│     valeurs: UNVERIFIED | VERIFIED | SUSPENDED
├── verified_at           TIMESTAMPTZ
├── created_at            TIMESTAMPTZ NOT NULL
├── last_active_at        TIMESTAMPTZ
├── is_active             BOOLEAN DEFAULT TRUE
│
│   ── colonnes ajoutées par V16 (confidentialité) ──
├── profile_visibility    VARCHAR(20) DEFAULT 'PUBLIC'
│     valeurs: PUBLIC | FRIENDS | PRIVATE
├── show_age              BOOLEAN DEFAULT TRUE
├── show_last_active      BOOLEAN DEFAULT TRUE
├── show_location         BOOLEAN DEFAULT FALSE
├── allow_messages        VARCHAR(20) DEFAULT 'EVERYONE'
│     valeurs: EVERYONE | FRIENDS | NONE
└── show_on_map           BOOLEAN DEFAULT FALSE
```

**Relations sortantes :**
- `users.id` ← référencé par presque toutes les autres tables

---

### 2. Catalogue Activités

**Tables impliquées :** `categories`, `activities`

```
categories
├── id          UUID PK
├── name        VARCHAR(80) UNIQUE
├── icon        VARCHAR(80)          ← nom d'icône frontend
└── color_ramp  VARCHAR(30)          ← ex: 'blue-indigo'

activities
├── id          UUID PK
├── parent_id   UUID → activities(id)  ← sous-activités (auto-référence)
├── category_id UUID → categories(id) NOT NULL
├── name        VARCHAR(120) NOT NULL
├── slug        VARCHAR(150) UNIQUE   ← ex: 'running', 'yoga'
├── description VARCHAR(500)
├── embedding   vector(1536)          ← pgvector, recherche sémantique
└── created_at  TIMESTAMPTZ
```

**Relations :**
- `activities.category_id` → `categories.id` (RESTRICT on delete)
- `activities.parent_id` → `activities.id` (CASCADE on delete, nullable)

---

### 3. Activités pratiquées par l'utilisateur

**Tables impliquées :** `user_activities`

Représente le fait qu'un utilisateur pratique une activité donnée, avec son niveau et son format de pratique.

```
user_activities
├── id                  UUID PK
├── user_id             UUID → users(id) CASCADE
├── activity_id         UUID → activities(id) CASCADE
├── visible_on_map      BOOLEAN DEFAULT TRUE   ← contrôle visibilité carte
├── custom_description  VARCHAR(500)
├── level               VARCHAR(20) DEFAULT 'ANY'
│     valeurs: ANY | BEGINNER | INTERMEDIATE | ADVANCED
├── format              VARCHAR(10) DEFAULT 'ANY'
│     valeurs: ANY | SOLO | DUO | GROUP
└── created_at          TIMESTAMPTZ
```

**Contrainte unique :** `(user_id, activity_id)` — un utilisateur ne peut déclarer la même activité qu'une fois.

---

### 4. Programmes sportifs

**Tables impliquées :** `programs`, `schedules`, `program_media`

Un programme est une offre sportive récurrente créée par un utilisateur dans le cadre d'une de ses activités.

```
programs
├── id                        UUID PK
├── user_activity_id          UUID → user_activities(id) CASCADE   ← lie programme à (user + activité)
├── title                     VARCHAR(150) NOT NULL
├── description               TEXT
├── embedding                 vector(1536)   ← recherche sémantique
├── status                    VARCHAR(20) DEFAULT 'DRAFT'
│     valeurs: DRAFT | ACTIVE | PAUSED | ARCHIVED
├── is_public                 BOOLEAN DEFAULT TRUE
├── archived_at               TIMESTAMPTZ
├── created_at                TIMESTAMPTZ
├── updated_at                TIMESTAMPTZ
│
│   ── colonnes dénormalisées (cache) ──
├── organizer_name            VARCHAR(80)    ← copie de users.display_name
├── organizer_avatar_url      VARCHAR(500)   ← copie de users.avatar_url
├── next_session_at           TIMESTAMPTZ    ← prochain créneau futur (calculé)
│
│   ── colonnes ajoutées par V26 ──
├── duration_weeks            INTEGER        ← durée du programme (1–52)
├── sessions_per_week         INTEGER        ← séances par semaine (1–7)
├── session_duration_minutes  INTEGER        ← durée d'une séance en minutes (30–180)
├── preferred_days            INTEGER[]      ← jours préférés (0=Dim … 6=Sam)
├── preferred_time            VARCHAR(20)
│     valeurs: MORNING | AFTERNOON | EVENING | FLEXIBLE
├── max_participants          INTEGER        ← capacité max au niveau programme (2–100)
├── privacy                   VARCHAR(20) DEFAULT 'PUBLIC'
│     valeurs: PUBLIC | FRIENDS_ONLY | PRIVATE
├── goals                     TEXT           ← objectifs du programme
├── prerequisites             TEXT           ← prérequis
└── location_type             VARCHAR(20)
      valeurs: REMOTE | IN_PERSON | HYBRID
```

**Règle importante :** Pour obtenir l'organisateur d'un programme, il faut remonter la chaîne :
`programs.user_activity_id` → `user_activities.user_id` → `users.id`

```
schedules                              ← créneaux géolocalisés
├── id                UUID PK
├── program_id        UUID → programs(id) CASCADE
├── place_name        VARCHAR(200) NOT NULL
├── place_type        VARCHAR(10) NOT NULL
│     valeurs: PUBLIC | PRIVATE | ONLINE
├── location          GEOMETRY(Point, 4326) NOT NULL   ← PostGIS
├── address_public    VARCHAR(300)
├── show_exact_address BOOLEAN DEFAULT FALSE
├── starts_at         TIMESTAMPTZ NOT NULL
├── ends_at           TIMESTAMPTZ
├── recurrence_rule   VARCHAR(200)   ← format iCal, ex: 'FREQ=WEEKLY;BYDAY=SA'
├── max_participants  INTEGER
└── created_at        TIMESTAMPTZ

program_media                          ← photos/vidéos
├── id          UUID PK
├── program_id  UUID → programs(id) CASCADE
├── url         VARCHAR(500) NOT NULL
├── media_type  VARCHAR(10)   ← IMAGE | VIDEO
├── sort_order  INTEGER DEFAULT 0
└── created_at  TIMESTAMPTZ
```

---

### 5. Inscriptions aux programmes

**Tables impliquées :** `user_programs`, `program_activities`

Représente l'inscription d'un utilisateur à un programme et son suivi de progression.

```
user_programs
├── id                   UUID PK
├── user_id              UUID → users(id) CASCADE
├── program_id           UUID → programs(id) CASCADE
├── schedule_id          UUID → schedules(id) SET NULL   ← créneau choisi
├── status               VARCHAR(20) DEFAULT 'ACTIVE'
│     valeurs: ACTIVE | PAUSED | COMPLETED | LEFT
├── leave_reason         TEXT
├── progress_percentage  INTEGER DEFAULT 0
├── activities_completed INTEGER DEFAULT 0
├── activities_skipped   INTEGER DEFAULT 0
├── last_activity_at     TIMESTAMPTZ
├── joined_at            TIMESTAMPTZ
└── left_at              TIMESTAMPTZ
```

**Contrainte unique :** `(user_id, program_id, status)` — permet d'avoir un historique (ex: LEFT puis ACTIVE de nouveau).

```
program_activities                     ← suivi par activité dans l'inscription
├── id              UUID PK
├── user_program_id UUID → user_programs(id) CASCADE
├── activity_id     UUID   ← référence logique (pas de FK vers activities)
├── status          VARCHAR(20) DEFAULT 'PENDING'
│     valeurs: PENDING | COMPLETED | SKIPPED
├── completed_at    TIMESTAMPTZ
├── skipped_at      TIMESTAMPTZ
└── notes           TEXT
```

**Contrainte unique :** `(user_program_id, activity_id)`

---

### 6. Messagerie

**Tables impliquées :** `conversations`, `conversation_members`, `messages`

```
conversations
├── id                  UUID PK
├── type                VARCHAR(10) DEFAULT 'DIRECT'   ← DIRECT | GROUP
├── activity_context_id UUID → activities(id) SET NULL  ← contexte optionnel
├── created_at          TIMESTAMPTZ
└── last_message_at     TIMESTAMPTZ

conversation_members                   ← table de jointure N-N
├── conversation_id  UUID → conversations(id) CASCADE  } PK composite
├── user_id          UUID → users(id) CASCADE           }
├── joined_at        TIMESTAMPTZ
└── last_read_at     TIMESTAMPTZ   ← pour badges "non lu"

messages
├── id              UUID PK
├── conversation_id UUID → conversations(id) CASCADE
├── sender_id       UUID → users(id) CASCADE
├── content         VARCHAR(4000) NOT NULL
├── status          VARCHAR(15) DEFAULT 'SENT'
│     valeurs: SENT | DELIVERED | READ
├── sent_at         TIMESTAMPTZ
└── read_at         TIMESTAMPTZ
```

---

### 7. Avis et recommandations

**Tables impliquées :** `reviews`, `review_criteria`, `peer_recommendations`

```
reviews
├── id                   UUID PK
├── program_id           UUID → programs(id) CASCADE
├── reviewer_id          UUID → users(id) CASCADE
├── interaction_proof_id UUID → conversations(id) RESTRICT  ← preuve d'interaction réelle
├── score                FLOAT [1..5] NOT NULL
├── comment              VARCHAR(1000)
└── created_at           TIMESTAMPTZ
```

**Contrainte unique :** `(program_id, reviewer_id)` — un seul avis par personne par programme.
**Règle métier :** L'avis nécessite une conversation existante comme preuve (RESTRICT on delete).

```
review_criteria                        ← sous-scores détaillés
├── id            UUID PK
├── review_id     UUID → reviews(id) CASCADE
├── criterion_key VARCHAR(30)   ← ex: 'ponctualite', 'pedagogie', 'securite'
└── score         FLOAT [1..5]

peer_recommendations                   ← recommandations utilisateur → utilisateur
├── id                   UUID PK
├── from_user_id         UUID → users(id) CASCADE
├── to_user_id           UUID → users(id) CASCADE
├── interaction_proof_id UUID → conversations(id) RESTRICT
├── comment              VARCHAR(500)
└── created_at           TIMESTAMPTZ
```

**Contrainte unique :** `(from_user_id, to_user_id)` — une seule recommandation par paire.

---

### 8. Gamification — Badges

**Tables impliquées :** `badges`, `badge_awards`

```
badges
├── id                  UUID PK
├── code                VARCHAR(60) UNIQUE   ← ex: 'FIRST_PROGRAM', 'TOP_RATED'
├── category            VARCHAR(20)          ← CREATION | SOCIAL | REPUTATION | ACTIVITY
├── label               VARCHAR(120)
├── condition_type      VARCHAR(40)          ← ex: 'PROGRAMS_CREATED', 'AVERAGE_REVIEW_SCORE'
├── condition_threshold INTEGER              ← valeur seuil
└── icon                VARCHAR(80)

badge_awards                           ← table de jointure N-N
├── badge_id    UUID → badges(id) CASCADE   } PK composite
├── user_id     UUID → users(id) CASCADE    }
└── awarded_at  TIMESTAMPTZ
```

---

### 9. Notifications

**Tables impliquées :** `notifications`, `notification_prefs`, `device_tokens`

```
notifications
├── id       UUID PK
├── user_id  UUID → users(id) CASCADE
├── type     VARCHAR(40)   ← NEW_MESSAGE | NEW_REVIEW | NEW_BADGE | PROGRAM_REMINDER | NEW_PEER_REC
├── channel  VARCHAR(10)   ← PUSH | IN_APP | EMAIL
├── payload  JSONB         ← données variables selon le type
├── is_read  BOOLEAN DEFAULT FALSE
├── sent_at  TIMESTAMPTZ
└── read_at  TIMESTAMPTZ

notification_prefs                     ← préférences par type de notification
├── id                UUID PK
├── user_id           UUID → users(id) CASCADE
├── notification_type VARCHAR(40)
├── email_enabled     BOOLEAN DEFAULT TRUE
├── push_enabled      BOOLEAN DEFAULT TRUE
└── frequency         VARCHAR(20) DEFAULT 'IMMEDIATE'   ← IMMEDIATE | DAILY | WEEKLY
```

**Contrainte unique :** `(user_id, notification_type)`

```
device_tokens                          ← tokens Firebase par appareil
├── id           UUID PK
├── user_id      UUID → users(id) CASCADE
├── token        VARCHAR(500) UNIQUE
├── platform     VARCHAR(20)   ← IOS | ANDROID | WEB
├── device_name  VARCHAR(100)
├── created_at   TIMESTAMPTZ
└── last_used_at TIMESTAMPTZ
```

---

### 10. Carte — affichage des marqueurs

**Pas de table dédiée.** L'endpoint `/api/map/activities` agrège :

```
schedules.location  (GEOMETRY PostGIS)
    └── programs.user_activity_id
            └── user_activities.activity_id → activities.name / category.color_ramp
            └── user_activities.user_id     → users.display_name

users.location  (GEOMETRY PostGIS)
    + users.location_public = TRUE
    + user_activities.visible_on_map = TRUE
```

**Condition pour apparaître sur la carte :**
1. Le programme doit avoir `status != 'ARCHIVED'` et `is_public = TRUE`
2. Le schedule doit avoir `location IS NOT NULL` et `place_type = 'PUBLIC'`
3. L'utilisateur (organisateur) doit avoir `location_public = TRUE`

---

### 11. Modération — Signalements

**Tables impliquées :** `reports`

```
reports
├── id           UUID PK
├── reporter_id  UUID → users(id) CASCADE
├── target_type  VARCHAR(20)   ← USER | MESSAGE | PROGRAM
├── target_id    UUID          ← ID polymorphe (pas de FK stricte)
├── reason       VARCHAR(30)   ← SPAM | INAPPROPRIATE_CONTENT | MISLEADING_INFORMATION | ...
├── status       VARCHAR(20) DEFAULT 'OPEN'
│     valeurs: OPEN | RESOLVED | DISMISSED
├── created_at   TIMESTAMPTZ
└── resolved_at  TIMESTAMPTZ
```

---

### 12. Recherche sémantique

**Tables impliquées :** `search_logs`

```
search_logs
├── id              UUID PK
├── user_id         UUID → users(id) SET NULL   ← nullable (recherche anonyme)
├── raw_query       VARCHAR(500)
├── parsed_intent   JSONB       ← résultat de parsing LLM (activité, niveau, lieu…)
├── query_embedding vector(1536) ← pgvector, index HNSW
├── results_count   INTEGER
└── created_at      TIMESTAMPTZ
```

Colonnes `embedding` / `vector(1536)` présentes aussi dans `activities` et `programs` pour la recherche sémantique par similarité cosinus.

---

### 13. Journal de progression

**Tables impliquées :** `progression_entries`

```
progression_entries
├── id         UUID PK
├── program_id UUID → programs(id) CASCADE
├── user_id    UUID → users(id) CASCADE
├── title      VARCHAR(150)
├── content    TEXT
├── metrics    float[]   ← tableau de métriques libres (km, minutes, bpm…)
├── is_public  BOOLEAN DEFAULT TRUE
└── created_at TIMESTAMPTZ
```

---

### 14. Traçabilité RGPD

**Tables impliquées :** `audit_logs`

```
audit_logs
├── id          UUID PK
├── user_id     UUID → users(id) SET NULL   ← nullable si user supprimé
├── action_type VARCHAR(50)   ← CREATE | UPDATE | DELETE | EXPORT | LOGIN | LOGOUT
├── entity_type VARCHAR(50)   ← USER | PROGRAM | MESSAGE | …
├── entity_id   UUID
├── old_value   TEXT   ← JSON de l'ancienne valeur
├── new_value   TEXT   ← JSON de la nouvelle valeur
├── ip_address  VARCHAR(45)
├── user_agent  VARCHAR(255)
└── created_at  TIMESTAMP
```

---

## Résumé des clés étrangères critiques

| Relation | Type | On Delete |
|---|---|---|
| `user_activities.user_id` → `users.id` | N-1 | CASCADE |
| `user_activities.activity_id` → `activities.id` | N-1 | CASCADE |
| `programs.user_activity_id` → `user_activities.id` | N-1 | CASCADE |
| `schedules.program_id` → `programs.id` | N-1 | CASCADE |
| `user_programs.user_id` → `users.id` | N-1 | CASCADE |
| `user_programs.program_id` → `programs.id` | N-1 | CASCADE |
| `user_programs.schedule_id` → `schedules.id` | N-1 | SET NULL |
| `messages.conversation_id` → `conversations.id` | N-1 | CASCADE |
| `reviews.interaction_proof_id` → `conversations.id` | N-1 | **RESTRICT** |
| `peer_recommendations.interaction_proof_id` → `conversations.id` | N-1 | **RESTRICT** |
| `activities.parent_id` → `activities.id` | auto-référence | CASCADE |
| `activities.category_id` → `categories.id` | N-1 | **RESTRICT** |

> **RESTRICT** = impossible de supprimer une conversation si elle est référencée comme preuve dans un avis ou une recommandation.

---

## Valeurs d'enum importantes

| Colonne | Valeurs |
|---|---|
| `users.verification_status` | `UNVERIFIED` `VERIFIED` `SUSPENDED` |
| `users.profile_visibility` | `PUBLIC` `FRIENDS` `PRIVATE` |
| `users.allow_messages` | `EVERYONE` `FRIENDS` `NONE` |
| `user_activities.level` | `ANY` `BEGINNER` `INTERMEDIATE` `ADVANCED` `EXPERT` |
| `user_activities.format` | `ANY` `SOLO` `DUO` `GROUP` |
| `programs.status` | `DRAFT` `ACTIVE` `PAUSED` `ARCHIVED` |
| `schedules.place_type` | `PUBLIC` `PRIVATE` `ONLINE` |
| `user_programs.status` | `ACTIVE` `PAUSED` `COMPLETED` `LEFT` |
| `program_activities.status` | `PENDING` `COMPLETED` `SKIPPED` |
| `messages.status` | `SENT` `DELIVERED` `READ` |
| `notifications.channel` | `PUSH` `IN_APP` `EMAIL` |
| `reports.status` | `OPEN` `RESOLVED` `DISMISSED` |
| `device_tokens.platform` | `IOS` `ANDROID` `WEB` |

---

## Endpoints API

> **Base URL :** `/api` — Spring Boot, authentification JWT Bearer sauf mention contraire.  
> **WebSocket :** STOMP/SockJS sur `ws://host/ws/chat`  
> **Total :** 97 endpoints HTTP + 1 handler STOMP, répartis sur 16 contrôleurs.

---

### Auth — `/api/auth`

| Méthode | Chemin | Description | Auth |
|---------|--------|-------------|------|
| POST | `/api/auth/register` | Créer un compte | Non (rate-limited) |
| POST | `/api/auth/login` | Authentification → JWT | Non (rate-limited) |
| POST | `/api/auth/refresh` | Renouveler l'access token | Non |
| GET | `/api/auth/verify-email` | Vérifier l'email via token | Non |
| POST | `/api/auth/forgot-password` | Envoyer un email de reset | Non (rate-limited) |
| POST | `/api/auth/reset-password` | Réinitialiser le mot de passe | Non |
| POST | `/api/auth/logout` | Déconnexion | Oui |

---

### Utilisateurs — `/api/users`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/users` | Rechercher des utilisateurs (query + coords) |
| GET | `/api/users/me` | Profil privé de l'utilisateur courant |
| PUT | `/api/users/me` | Modifier son profil |
| PUT | `/api/users/me/location` | Mettre à jour sa position géographique |
| POST | `/api/users/me/avatar` | Uploader son avatar |
| GET | `/api/users/{id}` | Profil public d'un utilisateur |
| DELETE | `/api/users/me` | Désactiver son compte |
| POST | `/api/users/me/change-password` | Changer son mot de passe |
| GET | `/api/users/me/privacy` | Lire ses paramètres de confidentialité |
| PUT | `/api/users/me/privacy` | Modifier ses paramètres de confidentialité |

---

### Activités — `/api/categories`, `/api/activities`, `/api/users/me/activities`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/categories` | Lister toutes les catégories |
| GET | `/api/activities` | Rechercher des activités (filtre catégorie / texte) |
| GET | `/api/users/me/activities` | Lister ses activités déclarées |
| POST | `/api/users/me/activities` | Ajouter une activité à son profil |
| PUT | `/api/users/me/activities/{userActivityId}` | Modifier une activité de son profil |
| DELETE | `/api/users/me/activities/{userActivityId}` | Supprimer une activité de son profil |
| PATCH | `/api/users/me/activities/{userActivityId}/visibility` | Activer/désactiver la visibilité carte |
| PATCH | `/api/activities/{activityId}/icon` | Définir l'icône d'une activité (URL/nom) |
| POST | `/api/activities/{activityId}/icon/upload` | Uploader l'icône d'une activité |

---

### Programmes — `/api/programs`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/programs` | Créer un programme |
| GET | `/api/programs` | Lister ses programmes créés |
| GET | `/api/programs/{programId}` | Détail d'un programme |
| PUT | `/api/programs/{programId}` | Modifier un programme |
| DELETE | `/api/programs/{programId}` | Supprimer un programme |
| PATCH | `/api/programs/{programId}` | Mise à jour partielle d'un programme (brouillon) |
| POST | `/api/programs/{programId}/schedules` | Ajouter un créneau |
| PUT | `/api/programs/{programId}/schedules/{scheduleId}` | Modifier un créneau |
| DELETE | `/api/programs/{programId}/schedules/{scheduleId}` | Supprimer un créneau |
| POST | `/api/programs/{programId}/report` | Signaler un programme |

---

### Inscriptions aux programmes

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/programs/{programId}/join` | S'inscrire à un programme |
| POST | `/api/programs/{programId}/leave` | Quitter un programme |
| GET | `/api/programs/{programId}/participants/count` | Nombre de participants actifs |
| GET | `/api/programs/{programId}/enrollment-status` | Vérifier son statut d'inscription |
| GET | `/api/users/me/programs` | Ses programmes rejoints (filtre par statut) |
| PATCH | `/api/users/me/programs/{userProgramId}` | Mettre à jour son avancement (%) |
| DELETE | `/api/users/me/programs/{userProgramId}` | Se désinscrire |
| POST | `/api/users/me/programs/{userProgramId}/activities/{activityId}/complete` | Marquer une activité comme terminée |
| POST | `/api/users/me/programs/{userProgramId}/activities/{activityId}/skip` | Passer une activité |

---

### Messagerie — `/api/conversations`, `/api/messages`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/conversations` | Créer une conversation |
| GET | `/api/conversations` | Lister ses conversations |
| GET | `/api/conversations/{conversationId}` | Détail d'une conversation |
| DELETE | `/api/conversations/{conversationId}` | Supprimer une conversation |
| POST | `/api/conversations/{conversationId}/messages` | Envoyer un message (REST) |
| GET | `/api/conversations/{conversationId}/messages` | Lire les messages d'une conversation |
| POST | `/api/conversations/{conversationId}/read` | Marquer la conversation comme lue |
| POST | `/api/conversations/{conversationId}/read-all` | Marquer tous les messages comme lus |
| POST | `/api/conversations/{conversationId}/images` | Uploader une image dans une conversation |
| PATCH | `/api/messages/{messageId}` | Modifier un message |
| DELETE | `/api/messages/{messageId}` | Supprimer un message |
| WS `@MessageMapping` | `/chat.send` | Envoyer un message via STOMP/WebSocket |

---

### Badges — `/api/badges`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/badges` | Lister tous les badges disponibles |
| GET | `/api/badges/me` | Ses badges obtenus |
| GET | `/api/badges/me/count` | Nombre de ses badges |
| POST | `/api/badges/me/evaluate` | Déclencher l'évaluation et attribution des badges |
| GET | `/api/badges/users/{userId}` | Badges d'un utilisateur spécifique |

---

### Carte — `/api/map`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/map/activities` | Activités visibles sur la carte |
| GET | `/api/map/users` | Utilisateurs visibles (filtre bounds/rayon) |
| GET | `/api/map/clusters` | Clusters d'utilisateurs pour la carte |
| GET | `/api/map/bounds` | Tous les marqueurs dans une zone (users + programmes + activités) |
| GET | `/api/map/nearby/{type}` | Éléments proches par type, lat/lng, rayon |
| GET | `/api/map/geocode` | Géocoder une adresse en coordonnées |
| GET | `/api/map/reverse-geocode` | Géocoder des coordonnées en adresse |
| POST | `/api/map/location` | Mettre à jour sa position depuis la carte |

---

### Médias — `/api/media`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/media/upload/image` | Uploader et traiter une image |
| POST | `/api/media/upload/avatar` | Uploader et traiter un avatar |
| GET | `/api/media/files/**` | Servir / streamer un fichier stocké |
| DELETE | `/api/media/files/**` | Supprimer un fichier stocké |

---

### Notifications — `/api/notifications`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/notifications` | Ses notifications in-app (paginé) |
| GET | `/api/notifications/unread-count` | Nombre de notifications non lues |
| PUT | `/api/notifications/{id}/read` | Marquer une notification comme lue |
| PUT | `/api/notifications/read-all` | Marquer toutes les notifications comme lues |
| DELETE | `/api/notifications/{id}` | Supprimer une notification |
| GET | `/api/notifications/preferences` | Lire ses préférences de notification |
| PUT | `/api/notifications/preferences` | Modifier ses préférences de notification |
| POST | `/api/notifications/devices` | Enregistrer un token push |
| DELETE | `/api/notifications/devices/{token}` | Désinscrire un token push |
| GET | `/api/notifications/devices` | Lister ses tokens push enregistrés |

---

### Progression — `/api/progressions`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/progressions` | Créer une entrée de progression |
| GET | `/api/progressions/my` | Ses entrées de progression |
| GET | `/api/progressions/my/streak` | Sa série d'activité (streak) |
| GET | `/api/progressions/my/stats` | Ses statistiques de progression |
| GET | `/api/progressions/{id}` | Détail d'une entrée |
| PUT | `/api/progressions/{id}` | Modifier une entrée |
| DELETE | `/api/progressions/{id}` | Supprimer une entrée |
| GET | `/api/progressions/program/{programId}` | Progressions d'un programme |
| GET | `/api/progressions/user/{userId}` | Progressions d'un utilisateur |

---

### Recommandations — `/api/recommendations`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/recommendations` | Créer une recommandation |
| GET | `/api/recommendations/received` | Recommandations reçues |
| GET | `/api/recommendations/given` | Recommandations données |
| GET | `/api/recommendations/me/stats` | Ses statistiques de recommandation |
| GET | `/api/recommendations/users/{userId}` | Recommandations publiques d'un utilisateur |
| GET | `/api/recommendations/stats/{userId}` | Statistiques de recommandation d'un utilisateur |
| GET | `/api/recommendations/can-recommend/{userId}` | Vérifier si on peut recommander un utilisateur |

---

### Signalements — `/api/reports`

| Méthode | Chemin | Description | Rôle |
|---------|--------|-------------|------|
| POST | `/api/reports` | Signaler un utilisateur / programme / message | Tous |
| GET | `/api/reports/me` | Ses signalements soumis | Tous |
| GET | `/api/reports/pending` | Signalements en attente | MODERATOR / ADMIN |
| PUT | `/api/reports/{reportId}/review` | Traiter / résoudre un signalement | MODERATOR / ADMIN |

---

### Avis — `/api/reviews`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/reviews` | Créer un avis sur un programme |
| GET | `/api/reviews/programs/{programId}` | Avis paginés d'un programme |
| GET | `/api/reviews/programs/{programId}/summary` | Résumé agrégé des avis d'un programme |
| GET | `/api/reviews/me` | Ses avis soumis |
| GET | `/api/reviews/can-review/{programId}` | Vérifier si on peut noter un programme (sans contrainte conversation) |

---

### Recherche sémantique — `/api/search`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/search` | Recherche NLP / sémantique (programmes + utilisateurs) |
| GET | `/api/search/popular` | Recherches populaires (30 derniers jours) |
| GET | `/api/search/recent` | Ses recherches récentes |
| DELETE | `/api/search/recent` | Effacer son historique de recherche |
| GET | `/api/search/tags` | Rechercher des tags/catégories |
| GET | `/api/search/tags/popular` | Tags/catégories les plus utilisés |

---

### Indexation — `/api/indexation`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/indexation/stats` | Statistiques d'indexation / embeddings |
| POST | `/api/indexation/reindex/programs` | Réindexer tous les programmes |
| POST | `/api/indexation/reindex/activities` | Réindexer toutes les activités |
| POST | `/api/indexation/reindex/all` | Réindexer programmes + activités |

---

### RGPD — `/api/gdpr`

| Méthode | Chemin | Description |
|---------|--------|-------------|
| GET | `/api/gdpr/export` | Exporter ses données personnelles (Art. 15) |
| DELETE | `/api/gdpr/delete-account` | Demander la suppression définitive du compte (Art. 17, purge 30 j) |

---

### Administration — `/api/admin/seed` *(dev / staging uniquement)*

| Méthode | Chemin | Description |
|---------|--------|-------------|
| POST | `/api/admin/seed/demo/reset` | Supprimer et recréer les données de démo |
| POST | `/api/admin/seed/status` | Vérifier la disponibilité du contrôleur de seed |
