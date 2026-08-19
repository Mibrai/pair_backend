# Schéma de la base de données — Pair / MeetDo

> **Relevé le 18 août 2026** par introspection de l'instance PostgreSQL, et non déduit des
> fichiers de migration : ce document décrit les tables telles qu'elles existent après
> application des **58 migrations Flyway** (jusqu'à `V59__user_activity_category_announcement.sql`).
> Vue d'ensemble de l'application : [`ARCHITECTURE_BACKEND.md`](ARCHITECTURE_BACKEND.md).

Extensions activées : **uuid-ossp**, **PostGIS 3.4** (géolocalisation), **pgvector**
(recherche sémantique). Toutes les clés primaires sont des `UUID` avec
`DEFAULT gen_random_uuid()`, et toutes les colonnes temporelles sont des `TIMESTAMPTZ` —
à une exception près, signalée en section 14.

---

## Vue d'ensemble — les 33 tables applicatives

| Table | Rôle |
|---|---|
| `users` | Comptes, confidentialité et compteurs de confiance |
| `categories` | Catalogue de catégories d'activités |
| `activities` | Catalogue de référence des activités (hiérarchique) |
| `user_activities` | Activités pratiquées par un utilisateur |
| `programs` | Programmes créés par un utilisateur |
| `schedules` | Créneaux géolocalisés d'un programme |
| `program_media` | Photos/vidéos attachées à un programme |
| `user_programs` | Inscriptions d'un utilisateur à un programme |
| `program_activities` | Suivi d'activités dans une inscription |
| `slot_participations` | Inscriptions à un créneau précis |
| `attendances` | Confirmations de présence après un créneau |
| `slot_recaps` | Souvenir partagé d'une occurrence de créneau |
| `recap_vibe_votes` | Votes d'ambiance sur un recap |
| `recap_participant_consents` | Consentement d'identité par participant d'un recap |
| `conversations` | Fils de messagerie (direct, groupe, diffusion programme) |
| `conversation_members` | Participants d'une conversation |
| `messages` | Messages individuels |
| `message_edit_history` | Versions antérieures d'un message édité |
| `reviews` | Avis laissés sur un programme |
| `review_criteria` | Sous-scores d'un avis |
| `peer_recommendations` | Recommandations entre utilisateurs |
| `badges` | Définitions des badges |
| `badge_awards` | Badges obtenus par un utilisateur |
| `notifications` | Notifications émises |
| `notification_prefs` | Préférences de notification par type |
| `device_tokens` | Tokens push (Firebase) par appareil |
| `subscriptions` | Abonnements auteur / activité / catégorie |
| `activity_alerts` | Alertes de proximité par activité |
| `progressions` | Journal de progression *(table active)* |
| `progression_entries` | Journal de progression *(vestige `V9`, non mappé)* |
| `search_logs` | Historique des recherches |
| `reports` | Signalements (utilisateur, programme, message) |
| `audit_logs` | Traçabilité RGPD |

---

## Diagramme de dépendances

```
categories
    ├── activities ──────────────────────────────────────────────┐
    │       ├── user_activities                                   │
    │       │       ├── programs                                  │
    │       │       │       ├── schedules                         │
    │       │       │       │       ├── slot_participations       │
    │       │       │       │       ├── attendances               │
    │       │       │       │       └── slot_recaps               │
    │       │       │       │               ├── recap_vibe_votes  │
    │       │       │       │               └── recap_participant_consents
    │       │       │       ├── program_media                     │
    │       │       │       ├── user_programs ── program_activities
    │       │       │       ├── reviews ── review_criteria        │
    │       │       │       └── progressions                      │
    │       │       └── subscriptions (cible USER_ACTIVITY)       │
    │       └── activity_alerts                                   │
    └── subscriptions (cible CATEGORY)                            │
                                                                  │
users ───────────────────────────────────────────────────────────>┘
    ├── user_activities · user_programs · slot_participations · attendances
    ├── conversations ── conversation_members
    │       └── messages ── message_edit_history
    ├── reviews · peer_recommendations
    ├── badge_awards ──── badges
    ├── notifications · notification_prefs · device_tokens
    ├── subscriptions (cible AUTHOR)
    ├── activity_alerts · progressions · search_logs
    └── reports · audit_logs
```

---

## Détail par fonctionnalité

---

### 1. Utilisateurs

**Table :** `users`

```
users
├── id                      UUID PK                     DEFAULT gen_random_uuid()
├── email                   VARCHAR(255) NOT NULL UNIQUE
├── password_hash           VARCHAR(255) NOT NULL       ← BCrypt, force 12
├── phone                   VARCHAR(20)
├── display_name            VARCHAR(80) NOT NULL
├── bio                     VARCHAR(1000)
├── avatar_url              VARCHAR(500)
├── location                GEOMETRY(Point, 4326)       ← PostGIS, index GIST
├── blur_radius_m           INTEGER NOT NULL  DEFAULT 500    ← flou de position
├── location_public         BOOLEAN NOT NULL  DEFAULT FALSE
├── online_status_visible   BOOLEAN NOT NULL  DEFAULT FALSE
├── receive_messages        BOOLEAN NOT NULL  DEFAULT TRUE
├── verification_status     VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED'
├── verified_at             TIMESTAMPTZ
├── created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
├── last_active_at          TIMESTAMPTZ
├── is_active               BOOLEAN NOT NULL  DEFAULT TRUE
│
│   ── confidentialité (V16) ──
├── profile_visibility      VARCHAR(20) DEFAULT 'PUBLIC'    CHECK PUBLIC|FRIENDS|PRIVATE
├── show_age                BOOLEAN DEFAULT TRUE
├── show_last_active        BOOLEAN DEFAULT TRUE
├── show_location           BOOLEAN DEFAULT FALSE
├── allow_messages          VARCHAR(20) DEFAULT 'EVERYONE'  CHECK EVERYONE|FRIENDS|NONE
├── show_on_map             BOOLEAN DEFAULT FALSE
├── allow_subscriptions     VARCHAR(20) NOT NULL DEFAULT 'OPEN'  CHECK OPEN|NOBODY   (V58)
│
│   ── compteurs de confiance, dénormalisés (V41) ──
├── distinct_partners_count INTEGER NOT NULL DEFAULT 0   ← partenaires différents rencontrés
├── attendance_count        INTEGER NOT NULL DEFAULT 0   ← présences confirmées
├── current_streak_weeks    INTEGER NOT NULL DEFAULT 0   ← série de semaines actives
└── last_attendance_at      TIMESTAMPTZ
```

Les quatre compteurs sont recalculés par `PracticeStatsService` après chaque confirmation
de présence. **Trois contraintes `CHECK`** verrouillent `profile_visibility`,
`allow_messages` et `allow_subscriptions` : ce sont les seules colonnes « enum » du schéma
protégées au niveau base.

**Index :** `idx_users_email`, `idx_users_last_active`, `idx_users_profile_visibility`,
`idx_users_location` (GIST).

---

### 2. Catalogue d'activités

**Tables :** `categories`, `activities`

```
categories
├── id          UUID PK
├── name        VARCHAR(80) NOT NULL UNIQUE
├── icon        VARCHAR(80)                 ← nom d'icône Material
└── color_ramp  VARCHAR(30) NOT NULL        ← ex: 'blue-indigo' (normalisé par V46)

activities
├── id          UUID PK
├── parent_id   UUID → activities(id) CASCADE      ← sous-activités (auto-référence)
├── category_id UUID → categories(id) RESTRICT NOT NULL
├── name        VARCHAR(120) NOT NULL
├── slug        VARCHAR(150) NOT NULL UNIQUE       ← ex: 'running', 'yoga'
├── description VARCHAR(500)
├── embedding   vector(384)                        ← pgvector, index HNSW cosinus
├── icon        VARCHAR(80) NOT NULL DEFAULT 'sports'   (V22 · V23)
├── image_url   VARCHAR(500)                            (V38 · V39)
└── created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
```

> **`vector(384)`, pas 1536.** La migration `V48` a basculé les embeddings de l'API OpenAI
> vers un modèle local trilingue (DJL / ONNX Runtime). Toute requête ou tout code qui
> suppose encore 1536 dimensions échouera.

**Index :** `idx_activities_slug`, `idx_activities_category`,
`idx_activities_embedding` (HNSW, `vector_cosine_ops`, `m=16`, `ef_construction=64`).

---

### 3. Activités pratiquées

**Table :** `user_activities`

```
user_activities
├── id                    UUID PK
├── user_id               UUID → users(id) CASCADE NOT NULL
├── activity_id           UUID → activities(id) CASCADE NOT NULL
├── visible_on_map        BOOLEAN NOT NULL DEFAULT TRUE
├── custom_description    VARCHAR(500)
├── level                 VARCHAR(20) DEFAULT 'ANY'
├── format                VARCHAR(10) DEFAULT 'ANY'
├── created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
└── category_notified_at  TIMESTAMPTZ                          (V59)
```

`category_notified_at` marque la **première annonce localisée** de cette activité : il
empêche les abonnés à la catégorie d'être notifiés deux fois pour la même pastille.

**Unique :** `(user_id, activity_id)`.
**Index :** `idx_ua_user`, `idx_ua_activity`, `idx_ua_visible`.

---

### 4. Programmes

**Table :** `programs`

```
programs
├── id                        UUID PK
├── user_activity_id          UUID → user_activities(id) CASCADE NOT NULL
├── title                     VARCHAR(150) NOT NULL
├── description               TEXT
├── embedding                 vector(384)     ← recherche sémantique
├── search_vector             tsvector        ← plein texte, index GIN   (V21)
├── status                    VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
├── is_public                 BOOLEAN NOT NULL DEFAULT TRUE
├── archived_at               TIMESTAMPTZ
├── created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
├── updated_at                TIMESTAMPTZ
│
│   ── dénormalisations (cache d'affichage) ──
├── organizer_name            VARCHAR(80)     ← copie de users.display_name
├── organizer_avatar_url      VARCHAR(500)    ← copie de users.avatar_url
├── next_session_at           TIMESTAMPTZ     ← prochain créneau futur (calculé)
│
│   ── cadrage du programme (V26) ──
├── duration_weeks            INTEGER
├── sessions_per_week         INTEGER
├── session_duration_minutes  INTEGER
├── preferred_days            INTEGER[]       ← 0 = dimanche … 6 = samedi
├── preferred_time            VARCHAR(20)
├── max_participants          INTEGER
├── privacy                   VARCHAR(20) DEFAULT 'PUBLIC'
├── goals                     TEXT
├── prerequisites             TEXT
├── location_type             VARCHAR(20)
├── image_url                 VARCHAR(500)                          (V37)
│
├── allow_participant_messages BOOLEAN NOT NULL DEFAULT TRUE        (V52)
└── subscribers_notified_at   TIMESTAMPTZ                           (V55)
```

**Règle de navigation :** l'organisateur d'un programme s'obtient en remontant
`programs.user_activity_id` → `user_activities.user_id` → `users.id`. Il n'y a pas de
`programs.user_id`.

`allow_participant_messages` gouverne le droit d'un participant à écrire dans le fil du
programme ; `subscribers_notified_at` évite qu'un même programme notifie deux fois les
abonnés de son auteur.

**Index :** `idx_programs_user_activity`, `idx_programs_status`, `idx_programs_archived`,
`idx_programs_search_vector` (GIN), `idx_programs_embedding` (HNSW).

---

### 5. Créneaux

**Tables :** `schedules`, `program_media`

```
schedules
├── id                    UUID PK
├── program_id            UUID → programs(id) CASCADE NOT NULL
├── place_name            VARCHAR(200) NOT NULL
├── place_type            VARCHAR(10) NOT NULL        ← PUBLIC | PRIVATE | ONLINE
├── location              GEOMETRY(Point, 4326) NOT NULL   ← index GIST
├── address_public        VARCHAR(300)
├── city                  VARCHAR(120)                          (V40)
├── show_exact_address    BOOLEAN NOT NULL DEFAULT FALSE
├── starts_at             TIMESTAMPTZ NOT NULL
├── ends_at               TIMESTAMPTZ
├── recurrence_rule       VARCHAR(200)     ← RFC 5545, ex: 'FREQ=WEEKLY;BYDAY=SA'
├── max_participants      INTEGER
├── created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
│
│   ── ouverture aux partenaires (V40) ──
├── is_open_to_partners   BOOLEAN NOT NULL DEFAULT TRUE
├── status                VARCHAR(20) NOT NULL DEFAULT 'OPEN'   ← OPEN|FULL|CANCELLED|PAST
├── participant_count     INTEGER NOT NULL DEFAULT 0
├── welcome_note          VARCHAR(300)
│
├── reminder_sent_for     TIMESTAMPTZ      ← occurrence déjà rappelée   (V50)
├── last_occurrence_start TIMESTAMPTZ      ← rollover récurrent          (V57)
└── last_occurrence_end   TIMESTAMPTZ

program_media
├── id          UUID PK
├── program_id  UUID → programs(id) CASCADE NOT NULL
├── url         VARCHAR(500) NOT NULL
├── media_type  VARCHAR(10) NOT NULL              ← IMAGE | VIDEO
├── sort_order  INTEGER NOT NULL DEFAULT 0
└── created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Modèle d'occurrence.** Une ligne `schedules` ne représente **qu'une seule occurrence
bookable**, même quand `recurrence_rule` est renseignée. `RecurringSlotRolloverJob` fait
avancer `starts_at`/`ends_at` vers l'occurrence suivante toutes les 10 minutes, et
`last_occurrence_start`/`last_occurrence_end` gardent la trace de celle qui vient de
passer — c'est cette paire qui rattache une présence ou un recap à la bonne séance.

**Index :** `idx_schedules_program`, `idx_schedules_starts_at`, `idx_schedules_status`,
`idx_schedules_open (is_open_to_partners, starts_at)`, `idx_schedules_location` (GIST), et
l'index partiel `idx_schedules_reminder_sweep ON (starts_at) WHERE status IN ('OPEN','FULL')`,
taillé pour le balayage du job de rappel.

---

### 6. Inscriptions aux programmes

**Tables :** `user_programs`, `program_activities`

```
user_programs
├── id                   UUID PK
├── user_id              UUID → users(id) CASCADE NOT NULL
├── program_id           UUID → programs(id) CASCADE NOT NULL
├── schedule_id          UUID → schedules(id) SET NULL   ← créneau choisi
├── status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
├── leave_reason         TEXT
├── progress_percentage  INTEGER NOT NULL DEFAULT 0
├── activities_completed INTEGER NOT NULL DEFAULT 0
├── activities_skipped   INTEGER NOT NULL DEFAULT 0
├── last_activity_at     TIMESTAMPTZ
├── joined_at            TIMESTAMPTZ NOT NULL DEFAULT now()
└── left_at              TIMESTAMPTZ

program_activities
├── id              UUID PK
├── user_program_id UUID → user_programs(id) CASCADE NOT NULL
├── activity_id     UUID NOT NULL     ← référence logique, PAS de FK vers activities
├── status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
├── completed_at    TIMESTAMPTZ
├── skipped_at      TIMESTAMPTZ
└── notes           TEXT
```

**Unique :** `user_programs (user_id, program_id, status)` — permet l'historique
(`LEFT` puis `ACTIVE` de nouveau) ; `program_activities (user_program_id, activity_id)`.

---

### 7. Participation aux créneaux et présence

**Tables :** `slot_participations`, `attendances`

```
slot_participations                    ← qui a rejoint quel créneau      (V40)
├── id           UUID PK
├── schedule_id  UUID → schedules(id) CASCADE NOT NULL
├── user_id      UUID → users(id) NOT NULL
├── status       VARCHAR(20) NOT NULL DEFAULT 'INTERESTED'
│                  ← INTERESTED | CONFIRMED | DECLINED | WITHDRAWN
├── join_message VARCHAR(300)
└── created_at   TIMESTAMPTZ NOT NULL DEFAULT now()

attendances                            ← présence confirmée après coup   (V41)
├── id                UUID PK
├── schedule_id       UUID → schedules(id) CASCADE NOT NULL
├── user_id           UUID → users(id) NOT NULL
├── was_present       BOOLEAN NOT NULL
├── attended_at       TIMESTAMPTZ NOT NULL     ← début de l'occurrence concernée
├── confirmed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
├── memory_photo_url  VARCHAR(500)
└── memory_is_public  BOOLEAN NOT NULL DEFAULT FALSE
```

**Unique :** `slot_participations (schedule_id, user_id)` — une seule ligne par personne et
par créneau ; `attendances (schedule_id, user_id, attended_at)` — **une par occurrence**,
ce qui rend un créneau récurrent confirmable semaine après semaine.

Une double confirmation de présence sur le même créneau constitue la preuve d'interaction
`SHARED_ATTENDANCE`, qui débloque la recommandation entre pairs (section 9).

**Index :** `idx_slotpart_schedule|user|status`, `idx_attendance_user_date (user_id, attended_at DESC)`,
`idx_attendance_schedule`, `idx_attendance_schedule_occurrence (schedule_id, attended_at)`.

---

### 8. Recaps de créneau *(V54 · V57)*

**Tables :** `slot_recaps`, `recap_vibe_votes`, `recap_participant_consents`

```
slot_recaps                            ← un souvenir par OCCURRENCE
├── id               UUID PK
├── schedule_id      UUID → schedules(id) CASCADE NOT NULL
├── occurrence_start TIMESTAMPTZ NOT NULL     ← identité de l'occurrence
├── occurrence_end   TIMESTAMPTZ NOT NULL
├── visibility       VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
│                      ← PRIVATE | PARTICIPANTS | PUBLIC
├── host_note        VARCHAR(400)
├── attendee_count   INTEGER NOT NULL DEFAULT 0    ← recalculé depuis attendances
├── published_at     TIMESTAMPTZ
├── created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
└── updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

recap_vibe_votes                       ← ambiance ressentie, max 2 par personne
├── id         UUID PK
├── recap_id   UUID → slot_recaps(id) CASCADE NOT NULL
├── user_id    UUID → users(id) CASCADE NOT NULL
├── vibe       VARCHAR(30) NOT NULL
│                RELAXED | ENERGETIC | FRIENDLY | TECHNICAL
│                BEGINNER_FRIENDLY | GOOD_LAUGH | FOCUSED | OUTDOORS
└── created_at TIMESTAMPTZ NOT NULL DEFAULT now()

recap_participant_consents             ← PK composite (recap_id, user_id)
├── recap_id      UUID → slot_recaps(id) CASCADE NOT NULL
├── user_id       UUID → users(id) CASCADE NOT NULL
├── show_identity BOOLEAN NOT NULL DEFAULT FALSE   ← opt-in, jamais par défaut
└── created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Unique :** `slot_recaps (schedule_id, occurrence_start)` — un recap et un seul par séance,
y compris pour un créneau récurrent ; `recap_vibe_votes (recap_id, user_id, vibe)`.

Règles appliquées côté service, pas en base : fenêtre de contribution de **7 jours** après
`occurrence_end`, **2 vibes** maximum par personne, **3 vibes** affichées au sommet,
**3 photos** maximum.

**Index :** `idx_recaps_schedule`, `idx_recaps_visibility (visibility, published_at DESC)`,
`idx_recaps_occurrence (occurrence_start DESC)`, `idx_vibe_recap`.

---

### 9. Messagerie

**Tables :** `conversations`, `conversation_members`, `messages`, `message_edit_history`

```
conversations
├── id                  UUID PK
├── type                VARCHAR(30) NOT NULL DEFAULT 'DIRECT'
│                         ← DIRECT | GROUP | PROGRAM_BROADCAST        (V53)
├── activity_context_id UUID → activities(id) SET NULL
├── program_id          UUID → programs(id) SET NULL                  (V51)
├── schedule_id         UUID → schedules(id) SET NULL                 (V51)
├── created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
└── last_message_at     TIMESTAMPTZ

conversation_members                   ← PK composite (conversation_id, user_id)
├── conversation_id UUID → conversations(id) CASCADE NOT NULL
├── user_id         UUID → users(id) CASCADE NOT NULL
├── joined_at       TIMESTAMPTZ NOT NULL DEFAULT now()
└── last_read_at    TIMESTAMPTZ        ← base du compteur « non lu »

messages
├── id              UUID PK
├── conversation_id UUID → conversations(id) CASCADE NOT NULL
├── sender_id       UUID → users(id) CASCADE NOT NULL
├── content         VARCHAR(4000) NOT NULL
├── status          VARCHAR(15) NOT NULL DEFAULT 'SENT'   ← SENT | DELIVERED | READ
├── sent_at         TIMESTAMPTZ NOT NULL DEFAULT now()
├── read_at         TIMESTAMPTZ
├── edited_at       TIMESTAMPTZ                           (V17)
├── deleted_at      TIMESTAMPTZ    ← suppression douce    (V17)
└── image_url       VARCHAR(500)                          (V17)

message_edit_history                                      (V17)
├── id               UUID PK
├── message_id       UUID → messages(id) CASCADE NOT NULL
├── previous_content VARCHAR(4000) NOT NULL
└── edited_at        TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Index unique partiel :**
`uq_conversations_program_broadcast ON conversations(program_id) WHERE type = 'PROGRAM_BROADCAST'`
— **un seul fil de diffusion par programme**, garanti en base et non par le code.

**Index :** `idx_conv_last_message`, `idx_conversations_program`,
`idx_messages_conversation|sender|sent_at`, `idx_edit_history_message|edited_at`.

---

### 10. Avis et recommandations

**Tables :** `reviews`, `review_criteria`, `peer_recommendations`

```
reviews
├── id                     UUID PK
├── program_id             UUID → programs(id) CASCADE NOT NULL
├── reviewer_id            UUID → users(id) CASCADE NOT NULL
├── interaction_proof_id   UUID → conversations(id) RESTRICT   ← preuve d'interaction
├── interaction_proof_type VARCHAR(20)     ← CONVERSATION | SHARED_ATTENDANCE   (V43)
├── score                  FLOAT NOT NULL  CHECK (score BETWEEN 1 AND 5)
├── comment                VARCHAR(1000)
├── created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
│
│   ── colonnes de compatibilité (V19), non mappées par l'entité ──
├── overall_rating         INTEGER
├── criteria_scores        JSONB
├── conversation_id        UUID
└── updated_at             TIMESTAMPTZ DEFAULT now()

review_criteria
├── id            UUID PK
├── review_id     UUID → reviews(id) CASCADE NOT NULL
├── criterion_key VARCHAR(30) NOT NULL   ← AMBIANCE | LEVEL_FIT | PUNCTUALITY | WELCOME
└── score         FLOAT NOT NULL  CHECK (score BETWEEN 1 AND 5)

peer_recommendations
├── id                     UUID PK
├── recommender_id         UUID → users(id) CASCADE NOT NULL
├── recommended_id         UUID → users(id) CASCADE NOT NULL
├── conversation_id        UUID → conversations(id) RESTRICT
├── interaction_proof_type VARCHAR(20)                                  (V43)
├── comment                VARCHAR(500)
├── rating                 INTEGER      ← 1–5, facultatif               (V20 · V45)
├── activity_context       UUID → activities(id)                        (V20)
├── program_context        UUID → programs(id)                          (V20)
├── created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
└── updated_at             TIMESTAMPTZ DEFAULT now()
```

> **Nommage corrigé.** Les colonnes s'appellent `recommender_id` / `recommended_id`, et non
> `from_user_id` / `to_user_id` — les index portent encore les anciens noms
> (`idx_peer_rec_from`, `idx_peer_rec_to`). De même pour `reviews`, dont la preuve est
> `interaction_proof_id`, la colonne `conversation_id` étant un doublon inutilisé.

**Unique :** `reviews (program_id, reviewer_id)` ; `peer_recommendations (recommender_id, recommended_id)`.

---

### 11. Badges

**Tables :** `badges`, `badge_awards`

```
badges
├── id                  UUID PK
├── code                VARCHAR(60) NOT NULL UNIQUE
├── category            VARCHAR(20) NOT NULL
│                         TRUST | ACHIEVEMENT | ROLE | VERIFICATION | ENGAGEMENT
│                         SPECIAL | CREATION | SOCIAL | REPUTATION | ACTIVITY
├── label               VARCHAR(120) NOT NULL
├── condition_type      VARCHAR(40) NOT NULL   ← 21 valeurs, voir section 16
├── condition_threshold INTEGER
└── icon                VARCHAR(80)

badge_awards                           ← PK composite (badge_id, user_id)
├── badge_id   UUID → badges(id) CASCADE NOT NULL
├── user_id    UUID → users(id) CASCADE NOT NULL
└── awarded_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

---

### 12. Notifications

**Tables :** `notifications`, `notification_prefs`, `device_tokens`

```
notifications
├── id      UUID PK
├── user_id UUID → users(id) CASCADE NOT NULL
├── type    VARCHAR(40) NOT NULL    ← 30 valeurs, voir section 16
├── channel VARCHAR(10) NOT NULL    ← EMAIL | PUSH | IN_APP
├── payload JSONB                   ← données variables selon le type
├── is_read BOOLEAN NOT NULL DEFAULT FALSE
├── sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
└── read_at TIMESTAMPTZ

notification_prefs
├── id                UUID PK
├── user_id           UUID → users(id) CASCADE NOT NULL
├── notification_type VARCHAR(40) NOT NULL
├── email_enabled     BOOLEAN NOT NULL DEFAULT TRUE
├── push_enabled      BOOLEAN NOT NULL DEFAULT TRUE
└── frequency         VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE'
                        ← IMMEDIATE | DAILY_DIGEST | WEEKLY

device_tokens
├── id           UUID PK
├── user_id      UUID → users(id) CASCADE NOT NULL
├── token        VARCHAR(500) NOT NULL UNIQUE
├── platform     VARCHAR(20) NOT NULL    ← ANDROID | IOS | WEB
├── device_name  VARCHAR(100)
├── locale       VARCHAR(10)             ← langue du push             (V49)
├── timezone     VARCHAR(64)             ← fuseau pour « dans 2 h »   (V56)
├── created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
└── last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

`locale` et `timezone` sont portées **par appareil** et non par utilisateur : le push est
composé et horodaté dans la langue et le fuseau du terminal qui le reçoit.

**Unique :** `notification_prefs (user_id, notification_type)`.

---

### 13. Abonnements *(V36 · V58)*

**Table :** `subscriptions`

```
subscriptions
├── id                      UUID PK
├── subscriber_id           UUID → users(id) CASCADE NOT NULL
├── type                    VARCHAR(20) NOT NULL   ← AUTHOR | USER_ACTIVITY | CATEGORY
├── target_author_id        UUID → users(id) CASCADE
├── target_user_activity_id UUID → user_activities(id) CASCADE
├── target_category_id      UUID → categories(id) CASCADE
├── level                   VARCHAR(20) NOT NULL DEFAULT 'ALL'
│                             CHECK ALL | NEW_ONLY | MUTED
├── lat                     DOUBLE PRECISION   ┐
├── lng                     DOUBLE PRECISION   ├ portée géographique, CATEGORY uniquement
├── radius_meters           INTEGER            ┘
└── created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
```

C'est la table la plus contrainte du schéma — **quatre `CHECK`** :

1. **Cohérence type ↔ cible** : exactement une des trois colonnes `target_*` est renseignée,
   et c'est celle qui correspond au `type`.
2. **Pas d'auto-abonnement** : `subscriber_id <> target_author_id`.
3. **Niveau** dans `ALL | NEW_ONLY | MUTED`.
4. **Portée géographique** : les trois colonnes `lat`/`lng`/`radius_meters` sont toutes
   nulles, ou toutes renseignées avec `type = 'CATEGORY'`, `lat ∈ [-90, 90]`,
   `lng ∈ [-180, 180]` et `radius_meters ∈ [1, 200 000]`.

**Unicité par trois index partiels**, un par type — `uq_sub_author`, `uq_sub_user_activity`,
`uq_sub_category` — plutôt qu'une contrainte unique globale, qui ne pourrait pas ignorer
les colonnes `target_*` nulles.

---

### 14. Alertes de proximité *(V42)*

**Table :** `activity_alerts`

```
activity_alerts
├── id                UUID PK
├── user_id           UUID → users(id) CASCADE NOT NULL
├── activity_id       UUID → activities(id) NOT NULL
├── location          GEOMETRY(Point, 4326) NOT NULL   ← index GIST
├── radius_meters     INTEGER NOT NULL DEFAULT 10000
├── is_active         BOOLEAN NOT NULL DEFAULT TRUE
├── last_triggered_at TIMESTAMPTZ
└── created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Unique :** `(user_id, activity_id)` — une alerte par activité et par personne.

---

### 15. Progression, recherche, modération, audit

```
progressions                           ← table ACTIVE (V18)
├── id            UUID PK
├── program_id    UUID → programs(id) CASCADE NOT NULL
├── user_id       UUID → users(id) CASCADE NOT NULL
├── title         VARCHAR(150)
├── content       TEXT
├── metrics       DOUBLE PRECISION[]   ← valeurs libres (km, minutes, bpm…)
├── metric_labels TEXT[]               ← libellés en regard des valeurs
├── is_public     BOOLEAN NOT NULL DEFAULT FALSE
├── created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
└── updated_at    TIMESTAMPTZ

progression_entries                    ← VESTIGE (V9), aucune entité ne la mappe
└── … même forme, sans metric_labels ni updated_at, is_public DEFAULT TRUE

search_logs
├── id              UUID PK
├── user_id         UUID → users(id) SET NULL   ← nullable (recherche anonyme)
├── raw_query       VARCHAR(500) NOT NULL
├── parsed_intent   TEXT           ← JSON sérialisé, colonne TEXT et non JSONB (V20)
├── query_embedding vector(384)    ← index HNSW cosinus
├── results_count   INTEGER
├── search_method   VARCHAR(50) DEFAULT 'fulltext'                   (V19)
└── searched_at     TIMESTAMPTZ NOT NULL DEFAULT now()

reports
├── id                    UUID PK
├── reporter_id           UUID → users(id) CASCADE NOT NULL
├── reported_entity_type  VARCHAR(20) NOT NULL   ← USER | PROGRAM | MESSAGE
├── reported_entity_id    UUID NOT NULL          ← polymorphe, pas de FK
├── reason                VARCHAR(30) NOT NULL
├── status                VARCHAR(20) NOT NULL DEFAULT 'OPEN'
├── description           VARCHAR(500)                               (V20)
├── reviewed_by           UUID                                       (V20)
├── reviewed_at           TIMESTAMPTZ                                (V20)
├── resolution_notes      TEXT                                       (V20)
├── created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
├── resolved_at           TIMESTAMPTZ
└── updated_at            TIMESTAMPTZ DEFAULT now()

audit_logs
├── id          UUID PK
├── user_id     UUID → users(id) SET NULL
├── action_type VARCHAR(50) NOT NULL
├── entity_type VARCHAR(50) NOT NULL
├── entity_id   UUID
├── old_value   TEXT      ← JSON de l'ancienne valeur
├── new_value   TEXT      ← JSON de la nouvelle valeur
├── ip_address  VARCHAR(45)
├── user_agent  VARCHAR(255)
└── created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP    ← SANS fuseau
```

> **`audit_logs.created_at` est la seule colonne temporelle du schéma sans fuseau horaire**
> (`TIMESTAMP` et non `TIMESTAMPTZ`). Comparer un horodatage d'audit à n'importe quel autre
> horodatage de la base demande une conversion explicite.

**Index :** `idx_progressions_program|user` (composés avec `created_at`),
`idx_search_log_user|created`, `idx_search_logs_embedding` (HNSW),
`idx_reports_reporter|target|status`, `idx_audit_logs_user_id|action_type|entity|created_at`.

---

## Clés étrangères — politique de suppression

La règle générale est **`ON DELETE CASCADE`** : supprimer un utilisateur emporte ses
activités, programmes, messages, participations, présences, recaps, abonnements, appareils,
alertes et badges.

**Trois `RESTRICT`**, tous délibérés :

| Contrainte | Raison |
|---|---|
| `activities.category_id` → `categories.id` | on ne supprime pas une catégorie encore utilisée |
| `reviews.interaction_proof_id` → `conversations.id` | la preuve d'interaction ne doit pas disparaître sous un avis |
| `peer_recommendations.conversation_id` → `conversations.id` | idem pour une recommandation |

**Six `SET NULL`**, là où la référence n'est qu'un contexte optionnel :

| Contrainte |
|---|
| `user_programs.schedule_id` → `schedules.id` |
| `conversations.program_id` → `programs.id` |
| `conversations.schedule_id` → `schedules.id` |
| `conversations.activity_context_id` → `activities.id` |
| `search_logs.user_id` → `users.id` |
| `audit_logs.user_id` → `users.id` |

**Trois FK sans action explicite** (donc `NO ACTION`) :

| Contrainte | Effet |
|---|---|
| `attendances.user_id` → `users.id` | une présence bloque la suppression physique de l'utilisateur |
| `slot_participations.user_id` → `users.id` | une participation à un créneau aussi |
| `activity_alerts.activity_id` → `activities.id` | une alerte bloque la suppression de l'activité |

Ces trois-là échappent à la règle du reste du schéma, qui aurait laissé passer la
suppression en cascade. La suppression de compte passe donc par l'anonymisation
(`GdprService.anonymizeUserData`), pas par un `DELETE` direct.

---

## Index remarquables

| Type | Index |
|---|---|
| **GIST** (spatial) | `users(location)`, `schedules(location)`, `activity_alerts(location)` |
| **HNSW** (`vector_cosine_ops`, `m=16`, `ef_construction=64`) | `activities(embedding)`, `programs(embedding)`, `search_logs(query_embedding)` |
| **GIN** (plein texte) | `programs(search_vector)` |
| **Uniques partiels** | `uq_sub_author`, `uq_sub_user_activity`, `uq_sub_category`, `uq_conversations_program_broadcast` |
| **Partiel de balayage** | `idx_schedules_reminder_sweep ON schedules(starts_at) WHERE status IN ('OPEN','FULL')` |

---

## 16. Valeurs d'énumération

Toutes stockées en `VARCHAR` avec `@Enumerated(EnumType.STRING)` côté JPA. Sauf mention
« CHECK », **aucune contrainte base ne les valide** : la source de vérité est l'énumération Java.

| Colonne | Valeurs | Défaut |
|---|---|---|
| `users.verification_status` | `UNVERIFIED` `EMAIL_VERIFIED` `PHONE_VERIFIED` `ID_VERIFIED` | `UNVERIFIED` |
| `users.profile_visibility` **CHECK** | `PUBLIC` `FRIENDS` `PRIVATE` | `PUBLIC` |
| `users.allow_messages` **CHECK** | `EVERYONE` `FRIENDS` `NONE` | `EVERYONE` |
| `users.allow_subscriptions` **CHECK** | `OPEN` `NOBODY` | `OPEN` |
| `user_activities.level` | `ANY` `BEGINNER` `INTERMEDIATE` `ADVANCED` `EXPERT` | `ANY` |
| `user_activities.format` | `ANY` `SOLO` `DUO` `GROUP` | `ANY` |
| `programs.status` | `DRAFT` `ACTIVE` `PAUSED` `ARCHIVED` | `DRAFT` |
| `programs.privacy` | `PUBLIC` `FRIENDS_ONLY` `PRIVATE` | `PUBLIC` |
| `programs.preferred_time` | `MORNING` `AFTERNOON` `EVENING` `FLEXIBLE` | — |
| `programs.location_type` | `REMOTE` `ONLINE` `IN_PERSON` `HYBRID` | — |
| `program_media.media_type` | `IMAGE` `VIDEO` | — |
| `schedules.place_type` | `PUBLIC` `PRIVATE` `ONLINE` | — |
| `schedules.status` | `OPEN` `FULL` `CANCELLED` `PAST` | `OPEN` |
| `slot_participations.status` | `INTERESTED` `CONFIRMED` `DECLINED` `WITHDRAWN` | `INTERESTED` |
| `user_programs.status` | `ACTIVE` `COMPLETED` `LEFT` `PAUSED` | `ACTIVE` |
| `program_activities.status` | `PENDING` `COMPLETED` `SKIPPED` | `PENDING` |
| `slot_recaps.visibility` | `PRIVATE` `PARTICIPANTS` `PUBLIC` | `PRIVATE` |
| `recap_vibe_votes.vibe` | `RELAXED` `ENERGETIC` `FRIENDLY` `TECHNICAL` `BEGINNER_FRIENDLY` `GOOD_LAUGH` `FOCUSED` `OUTDOORS` | — |
| `conversations.type` | `DIRECT` `GROUP` `PROGRAM_BROADCAST` | `DIRECT` |
| `messages.status` | `SENT` `DELIVERED` `READ` | `SENT` |
| `review_criteria.criterion_key` | `AMBIANCE` `LEVEL_FIT` `PUNCTUALITY` `WELCOME` | — |
| `reviews.interaction_proof_type`<br>`peer_recommendations.interaction_proof_type` | `CONVERSATION` `SHARED_ATTENDANCE` | — |
| `badges.category` | `TRUST` `ACHIEVEMENT` `ROLE` `VERIFICATION` `ENGAGEMENT` `SPECIAL` `CREATION` `SOCIAL` `REPUTATION` `ACTIVITY` | — |
| `badges.condition_type` | `VERIFICATION` `RECOMMENDATION_COUNT` `PROGRAM_COUNT` `PROGRESSION_STREAK` `ACTIVITY_DIVERSITY` `MANUAL` `PROGRAMS_CREATED` `CONVERSATIONS_STARTED` `AVERAGE_REVIEW_SCORE` `ACTIVITIES_REGISTERED` `RECOMMENDATIONS_RECEIVED` `ACTIVITIES_COMPLETED` `MORNING_SESSIONS` `GROUP_ENROLLMENTS` `PERFECT_REVIEWS` `STREAK_DAYS` `UNIQUE_ACTIVITIES` `ATTENDANCE_COUNT` `DISTINCT_PARTNERS` `WEEKLY_STREAK` `SLOT_HOSTED_COUNT` | — |
| `notifications.channel` | `EMAIL` `PUSH` `IN_APP` | — |
| `notification_prefs.frequency` | `IMMEDIATE` `DAILY_DIGEST` `WEEKLY` | `IMMEDIATE` |
| `device_tokens.platform` | `ANDROID` `IOS` `WEB` | — |
| `subscriptions.type` | `AUTHOR` `USER_ACTIVITY` `CATEGORY` | — |
| `subscriptions.level` **CHECK** | `ALL` `NEW_ONLY` `MUTED` | `ALL` |
| `reports.reported_entity_type` | `USER` `PROGRAM` `MESSAGE` | — |
| `reports.reason` | `SPAM` `HARASSMENT` `INAPPROPRIATE_CONTENT` `FAKE_PROFILE` `VIOLENCE` `HATE_SPEECH` `OTHER` | — |
| `reports.status` | `PENDING` `REVIEWED` `ACTIONED` `DISMISSED` | `OPEN` ⚠ |

**Les 30 valeurs de `notifications.type` :**
`NEW_MESSAGE` `NEW_MATCH` `NEARBY_PROGRAM` `NEW_FOLLOWER` `PEER_RECOMMENDATION`
`PROGRAM_REVIEW` `BADGE_EARNED` `PROGRAM_REMINDER` `PROGRESSION_REMINDER`
`ACCOUNT_VERIFICATION` `PASSWORD_RESET` `MODERATION_ACTION` `AUTHOR_NEW_ACTIVITY`
`AUTHOR_NEW_PROGRAM` `ACTIVITY_UPDATED` `ACTIVITY_NEW_PROGRAM` `CATEGORY_NEW_ACTIVITY`
`NEW_REVIEW` `NEW_BADGE` `NEW_PEER_REC` `MATCH_FOUND` `PROGRAM_CANCELLED`
`SCHEDULE_CHANGED` `SYSTEM` `SLOT_JOINED` `SLOT_CANCELLED` `ATTENDANCE_PROMPT`
`ACTIVITY_ALERT_MATCH` `STREAK_MILESTONE` `PARTNER_MILESTONE`

---

## 17. Divergences relevées entre la base et le code

Constatées en comparant les valeurs réellement stockées aux énumérations Java. Aucune n'est
bloquante aujourd'hui, mais chacune est un piège pour qui écrit une requête directe.

| # | Divergence | Conséquence |
|---|---|---|
| 1 | **`reports.status`** — défaut base `'OPEN'`, valeurs présentes `OPEN` / `RESOLVED` / `DISMISSED` ; l'enum `ReportStatus` dit `PENDING` / `REVIEWED` / `ACTIONED` / `DISMISSED` | une ligne insérée en SQL brut, ou une donnée de seed ancienne, lève une erreur de désérialisation JPA à la lecture |
| 2 | **`reports.reason`** — la base contient `MISLEADING_INFORMATION`, absente de `ReportReason` | même risque, sur des signalements de démo |
| 3 | **`badges.category`** — la base contient `ACTIVITY`, présente dans l'enum mais absente du jeu de badges documenté ailleurs | cohérent, simplement à ne pas oublier |
| 4 | **`progressions` / `progression_entries`** — deux tables pour un seul concept, une seule mappée | toute requête analytique doit viser `progressions` |
| 5 | **`reviews.conversation_id`** et les colonnes `overall_rating` / `criteria_scores` (V19) | non mappées, jamais alimentées par le code actuel |
| 6 | **`audit_logs.created_at`** en `TIMESTAMP` sans fuseau | conversion explicite requise pour toute jointure temporelle |
| 7 | **Index aux anciens noms** sur `peer_recommendations` (`idx_peer_rec_from` / `_to`) | cosmétique, mais trompeur à la lecture d'un `EXPLAIN` |

---

## 18. Reproduire ce relevé

```bash
# Tables et colonnes
docker exec pair-postgres psql -U pair_user -d pair_db -c "
  SELECT table_name, ordinal_position, column_name, data_type,
         character_maximum_length, is_nullable, column_default
  FROM information_schema.columns
  WHERE table_schema='public'
    AND table_name NOT IN ('spatial_ref_sys','flyway_schema_history')
  ORDER BY table_name, ordinal_position;"

# Contraintes (FK, uniques, CHECK)
docker exec pair-postgres psql -U pair_user -d pair_db -c "
  SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
  FROM pg_constraint WHERE connamespace='public'::regnamespace
  ORDER BY conrelid::regclass::text, contype;"

# Index
docker exec pair-postgres psql -U pair_user -d pair_db -c "
  SELECT tablename, indexname, indexdef FROM pg_indexes
  WHERE schemaname='public' ORDER BY tablename;"

# Niveau de migration atteint
docker exec pair-postgres psql -U pair_user -d pair_db -c "
  SELECT version, description, installed_on FROM flyway_schema_history
  ORDER BY installed_rank DESC LIMIT 5;"
```

---

## Endpoints API

La liste des routes vivait auparavant dans ce fichier ; elle y était incomplète (97 routes
recensées pour **179** réellement exposées) et n'a pas sa place dans un document de schéma.
Elle est désormais tenue à jour, contrôleur par contrôleur, dans
[`ARCHITECTURE_BACKEND.md`](ARCHITECTURE_BACKEND.md) § 4, et la référence exécutable reste
`/swagger-ui.html` sur une instance démarrée.
