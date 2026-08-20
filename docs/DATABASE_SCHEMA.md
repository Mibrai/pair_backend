# Schéma de la base de données — Pair / MeetDo

> **Relevé le 20 août 2026** par introspection, et non déduit des fichiers de migration :
> ce document décrit les tables telles qu'elles existent après application des
> **76 migrations Flyway** (jusqu'à `V77__trigram_search.sql`).
> Vue d'ensemble de l'application : [`ARCHITECTURE_BACKEND.md`](ARCHITECTURE_BACKEND.md).
>
> Relevé précédent : 18 août 2026, arrêté à `V59`. Les dix-huit migrations posées depuis
> — phases A à D du TODO v2, puis la spécification des liens publics — ajoutent six tables
> et une trentaine de colonnes. Les sections marquées *(nouveau)* n'existaient pas dans la
> version précédente de ce document.

Extensions activées : **uuid-ossp**, **PostGIS 3.6** (géolocalisation), **pgvector**
(recherche sémantique) et **pg_trgm** *(nouveau, V77 — tolérance aux fautes de frappe)*.
Toutes les clés primaires sont des `UUID` avec `DEFAULT gen_random_uuid()`, et toutes les
colonnes temporelles sont des `TIMESTAMPTZ` — à une exception près, signalée en section 23.

---

## Vue d'ensemble — les 39 tables applicatives

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
| `user_blocks` | Blocages entre comptes, bilatéraux à la lecture *(nouveau, V62)* |
| `slot_safety_shares` | Liens de sécurité vers un proche, périssables *(nouveau, V63)* |
| `slot_invitations` | Invitations nominatives ou par code à un créneau *(nouveau, V66)* |
| `user_languages` | Langues parlées et niveau déclaré *(nouveau, V71)* |
| `schedule_accessibility_tags` | Conditions d'accueil annoncées d'un créneau *(nouveau, V72)* |
| `user_availability` | Grille des disponibilités habituelles *(nouveau, V73)* |

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
    │       │       │       │       ├── slot_safety_shares        │
    │       │       │       │       ├── slot_invitations          │
    │       │       │       │       ├── schedule_accessibility_tags
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
    ├── user_blocks (blocker_id et blocked_id, deux fois vers users)
    ├── user_languages · user_availability
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
│   ── compteurs de confiance, dénormalisés (V41 · V69) ──
├── distinct_partners_count INTEGER NOT NULL DEFAULT 0   ← partenaires différents rencontrés
├── attendance_count        INTEGER NOT NULL DEFAULT 0   ← présences confirmées
├── current_streak_weeks    INTEGER NOT NULL DEFAULT 0   ← série de semaines actives
├── last_attendance_at      TIMESTAMPTZ
├── joined_slots_count      INTEGER NOT NULL DEFAULT 0   ← dénominateur du signal (V69)
│
│   ── parcours d'accueil (V60 · V74) ──
├── onboarding_step         VARCHAR(30)        ACTIVITIES|LEVELS|LOCATION|PREVIEW
├── onboarding_completed_at TIMESTAMPTZ                  ← une date, jamais un booléen
│
│   ── règles de communauté (V64) ──
├── guidelines_version      VARCHAR(10)
├── guidelines_accepted_at  TIMESTAMPTZ
│
│   ── heures de silence (V76) ──
├── quiet_hours_start       SMALLINT   0–23, NULL = désactivé
└── quiet_hours_end         SMALLINT   0–23  CHECK les deux ou aucun, et différents
```

Les cinq compteurs sont recalculés par `PracticeStatsService` après chaque confirmation
de présence. `joined_slots_count` est le **dénominateur** du signal de fiabilité, et
`attendance_count` son numérateur ; le rapport n'est jamais exposé, seul un libellé
qualitatif l'est.

**Quatre contraintes `CHECK`** verrouillent `profile_visibility`, `allow_messages`,
`allow_subscriptions` et les heures de silence : ce sont les seules colonnes « enum » ou
bornées du schéma protégées au niveau base. Celle des heures de silence impose que les deux
bornes soient posées ensemble et différentes — une fenêtre à moitié définie ne décrit rien,
et deux bornes égales se lisent aussi bien « une minute » que « toute la journée ».

**Pas de fuseau sur les heures de silence** : c'est celui de l'appareil
(`device_tokens.timezone`) qui décide, appareil par appareil, au moment de l'envoi. La
fenêtre traverse minuit dans le cas courant — « 22 → 7 » — et se lit par
`QuietHours`, jamais par une comparaison écrite à la main.

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
├── subscribers_notified_at   TIMESTAMPTZ                           (V55)
└── created_via               VARCHAR(20) NOT NULL DEFAULT 'FULL'   ← FULL | QUICK   (V61)
```

**Règle de navigation :** l'organisateur d'un programme s'obtient en remontant
`programs.user_activity_id` → `user_activities.user_id` → `users.id`. Il n'y a pas de
`programs.user_id`.

`allow_participant_messages` gouverne le droit d'un participant à écrire dans le fil du
programme ; `subscribers_notified_at` évite qu'un même programme notifie deux fois les
abonnés de son auteur.

`created_via` *(nouveau, V61)* distingue un programme rempli écran par écran d'un programme
né d'un **créneau rapide**. Le second n'a ni description ni cadrage : sans ce drapeau, il
s'affichait comme un programme mal rempli, et le client n'avait aucun moyen de distinguer un
vide assumé d'un oubli.

**Index :** `idx_programs_user_activity`, `idx_programs_status`, `idx_programs_archived`,
`idx_programs_search_vector` (GIN), `idx_programs_embedding` (HNSW),
`idx_programs_title_trgm` (GIN trigrammes, V77).

---

### 5. Créneaux

**Tables :** `schedules`, `program_media`

```
schedules
├── id                    UUID PK
├── program_id            UUID → programs(id) CASCADE NOT NULL
├── place_name            VARCHAR(200) NOT NULL
├── place_type            VARCHAR(10) NOT NULL        ← PUBLIC | PRIVATE | ONLINE
├── location              GEOMETRY(Point, 4326)            ← index GIST ; nullable depuis V61
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
├── last_occurrence_end   TIMESTAMPTZ
│
│   ── partage public (V65) ──
├── public_share_token    VARCHAR(22) UNIQUE   ← base62 opaque, jamais l'UUID interne
├── is_publicly_shareable BOOLEAN NOT NULL DEFAULT TRUE
├── public_view_count     INTEGER NOT NULL DEFAULT 0   ← robots d'aperçu exclus
│
│   ── annulation (V68) ──
├── cancelled_at          TIMESTAMPTZ
├── cancelled_by          UUID → users(id) SET NULL
├── cancellation_reason   VARCHAR(300)
│
└── primary_language      VARCHAR(5)       ← langue annoncée de la séance   (V71)

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

**`location` est devenue nullable en V61**, pour le créneau en ligne, et une contrainte
`CHECK (place_type = 'ONLINE' OR location IS NOT NULL)` a pris le relais de `NOT NULL` :
une séance à distance n'a pas de point, tout le reste en a un.

**Le jeton de partage n'est jamais rétro-rempli.** Un créneau que personne n'a partagé n'a
pas d'adresse publique ; il en obtient une à la première demande. Refermer le partage
(`is_publicly_shareable = FALSE`) **n'efface pas** le jeton : rouvrir doit rendre valides
les liens déjà collés ailleurs, et un jeton neuf transformerait une pause en rupture
définitive.

**`public_view_count` exclut les robots d'aperçu**, reconnus au `User-Agent`. Ils sont la
raison d'être de la page publique — leur visite fabrique la vignette — et un seul lien
partagé dans un groupe en déclenche plusieurs avant que quiconque n'ait cliqué. L'incrément
est un `UPDATE` atomique : deux ouvertures simultanées n'en comptaient qu'une.

**Index :** `idx_schedules_program`, `idx_schedules_starts_at`, `idx_schedules_status`,
`idx_schedules_open (is_open_to_partners, starts_at)`, `idx_schedules_location` (GIST),
`schedules_public_share_token_key` (unique), et l'index partiel
`idx_schedules_reminder_sweep ON (starts_at) WHERE status IN ('OPEN','FULL')`, taillé pour
le balayage du job de rappel.

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
├── created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
│
│   ── liste d'attente (V67) ──
├── status         … | WAITLISTED   ← le rang n'existe que dans cet état
├── waitlist_position INTEGER
├── promoted_at    TIMESTAMPTZ      ← passage de la file à la place
├── withdrawn_at   TIMESTAMPTZ      ← désistement volontaire
│
└── attendance_closed_at TIMESTAMPTZ  ← fenêtre de présence refermée   (V70)

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

**Unique partiel *(nouveau, V67)* :** `(schedule_id, waitlist_position) WHERE status =
'WAITLISTED'`. Le rang n'a de sens que dans la file : un partiel plutôt qu'un unique complet,
sinon deux personnes promues garderaient des rangs qui se disputeraient la contrainte.

**`attendance_closed_at` referme la fenêtre de présence** *(nouveau, V70)*. Un silence n'est
pas une absence : passé le délai, la question ne se pose plus, et la ligne sort du
dénominateur du signal de fiabilité au lieu d'y compter comme un « non ».

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
├── last_read_at    TIMESTAMPTZ        ← base du compteur « non lu »
│
│   ── confort de messagerie (V75) ──
├── muted_at        TIMESTAMPTZ        ← en sourdine depuis cette date
└── archived_at     TIMESTAMPTZ        ← rangé depuis cette date

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
├── image_url       VARCHAR(500)                          (V17)
│
│   ── partage de position ponctuel (V75) ──
├── location_lat        DOUBLE PRECISION
├── location_lng        DOUBLE PRECISION
└── location_expires_at TIMESTAMPTZ    ← 30 minutes au plus ; les trois ou aucune

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
`idx_messages_conversation|sender|sent_at`, `idx_edit_history_message|edited_at`, et
l'index partiel `idx_messages_location_expires ON (location_expires_at) WHERE
location_expires_at IS NOT NULL`, taillé pour le balayage d'effacement.

**Sourdine et archivage vivent sur l'appartenance**, pas sur la conversation : deux
personnes d'un même fil n'ont aucune raison de le classer pareil. La sourdine coupe
l'émission, pas la réception — le message arrive et compte dans le décompte du fil, il ne
sonne plus. L'archivage ne se défait pas tout seul : un message reçu ne ressort pas le fil
de l'archive, sinon ranger celui dont on veut se débarrasser n'aurait aucun effet.

Les deux sortent du **total** de `/api/conversations/unread-count` sans sortir du décompte
par fil : un badge d'icône annonce ce qui attend sur l'écran d'accueil, et pointer vers un
fil délibérément tu ou rangé serait un nombre qu'on ne saurait pas d'où faire retomber.

**Le partage de position est un message ordinaire**, et c'est toute la protection : il
apparaît dans le fil, donc suivre quelqu'un reste visible de celui qu'on suit. Les
coordonnées sont des flottants et non un point PostGIS — ce point n'est jamais interrogé
spatialement, et lui donner un type géographique l'aurait rangé avec les données que le
système interroge. **Un balayage efface les coordonnées échues** ; mais c'est la lecture qui
fait foi, elle ne sert jamais un point expiré, y compris entre l'échéance et le passage
suivant du job.

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

### 16. Blocage entre comptes *(nouveau, V62)*

**Table :** `user_blocks`

```
user_blocks
├── id         UUID PK
├── blocker_id UUID → users(id) CASCADE NOT NULL
├── blocked_id UUID → users(id) CASCADE NOT NULL
├── reason     VARCHAR(30)      ← facultatif, jamais montré au bloqué
└── created_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Unique :** `(blocker_id, blocked_id)`, plus un `CHECK (blocker_id <> blocked_id)` : on ne
se bloque pas soi-même.

**La ligne est dirigée, la lecture est bilatérale.** Une seule ligne suffit à rendre les deux
personnes invisibles l'une à l'autre — un masquage qui dépendrait du sens rendrait le
blocage détectable par comparaison. Le prédicat est un `NOT EXISTS` sur les deux sens, écrit
une fois dans `BlockSql` et concaténé dans les requêtes concernées.

**Il n'y a pas de point de passage unique.** Le dépôt n'a ni aspect ni intercepteur métier,
et un filtre Hibernate global serait inopérant, la plupart des requêtes concernées étant en
SQL natif. Le filtrage descend donc dans chaque requête et **dans son `COUNT`** :
post-filtrer casserait la pagination et ferait annoncer « Programmes (12) » puis en servir 9.

---

### 17. Lien de sécurité *(nouveau, V63)*

**Table :** `slot_safety_shares`

```
slot_safety_shares
├── id                   UUID PK
├── user_id              UUID → users(id) CASCADE NOT NULL
├── schedule_id          UUID → schedules(id) CASCADE NOT NULL
├── share_token          VARCHAR(22) UNIQUE NOT NULL   ← base62 opaque
├── expires_at           TIMESTAMPTZ NOT NULL
├── occurrence_starts_at TIMESTAMPTZ NOT NULL          ← figée à la création
├── occurrence_ends_at   TIMESTAMPTZ NOT NULL
├── viewed_at            TIMESTAMPTZ
└── created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
```

**Les deux colonnes d'occurrence ne figuraient pas dans la spécification, et elles sont
indispensables.** `RecurringSlotRolloverJob` fait avancer `schedules.starts_at` toutes les
dix minutes : un lien qui lirait le créneau annoncerait la séance de la semaine suivante à
un proche qui attend celle de ce soir. La date est donc **gelée** au moment du partage.

La page est lisible **sans compte** : son destinataire est un proche qui n'a pas meetDo, et
lui en demander un viderait la fonctionnalité de son sens. Toute la confidentialité repose
sur le jeton, opaque et périssable.

---

### 18. Invitations à un créneau *(nouveau, V66)*

**Table :** `slot_invitations`

```
slot_invitations
├── id           UUID PK
├── inviter_id   UUID → users(id) CASCADE NOT NULL
├── schedule_id  UUID → schedules(id) CASCADE       ← nullable : invitation hors créneau
├── invite_code  VARCHAR(22) UNIQUE NOT NULL
├── invitee_id   UUID → users(id) SET NULL          ← renseigné à l'inscription
├── created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
├── joined_at    TIMESTAMPTZ                        ← a rejoint le créneau
└── converted_at TIMESTAMPTZ                        ← a créé un compte
```

Deux dates plutôt qu'un statut : rejoindre un créneau et créer un compte sont deux événements
distincts, et l'un peut arriver sans l'autre. Le badge `HOST_INVITER` est semé par la même
migration.

---

### 19. Langues parlées *(nouveau, V71)*

**Table :** `user_languages` — PK composite `(user_id, language)`

```
user_languages
├── user_id     UUID → users(id) CASCADE NOT NULL
├── language    VARCHAR(5) NOT NULL     ← étiquette courte : fr, en, de
└── proficiency VARCHAR(20) NOT NULL    ← NATIVE | FLUENT | CONVERSATIONAL | BASIC
```

**Le filtre de langue n'exclut jamais faute d'information.** Une langue non déclarée veut
dire « on ne sait pas » : écarter le créneau punirait ceux qui n'ont rien rempli, qui sont la
majorité. À comparer avec la section suivante, où le choix est inverse — et délibérément.

---

### 20. Accessibilité d'un créneau *(nouveau, V72)*

**Table :** `schedule_accessibility_tags` — PK composite `(schedule_id, tag)`

```
schedule_accessibility_tags
├── schedule_id UUID → schedules(id) CASCADE NOT NULL
└── tag         VARCHAR(40) NOT NULL    ← étiquette de AccessibilityTag
```

**Ce filtre est restrictif, à l'inverse de celui des langues.** Une étiquette non déclarée
veut dire « rien ne permet de l'affirmer », et afficher quand même le créneau enverrait
quelqu'un en fauteuil vers un lieu dont personne n'a garanti l'accueil. Le coût de l'erreur
n'est pas du même ordre dans les deux sens.

Plusieurs étiquettes demandées **se cumulent** : qui filtre « accessible en fauteuil » ET
« sans alcool » a besoin des deux.

**Index :** `idx_schedule_accessibility_tag` — le filtre entre par l'étiquette, pas par le
créneau.

---

### 21. Disponibilités habituelles *(nouveau, V73)*

**Table :** `user_availability` — PK composite `(user_id, day_of_week, time_slot)`

```
user_availability
├── user_id     UUID → users(id) CASCADE NOT NULL
├── day_of_week SMALLINT NOT NULL  CHECK 1–7    ← numérotation ISO, celle d'EXTRACT(ISODOW)
└── time_slot   VARCHAR(20) NOT NULL            ← MORNING | AFTERNOON | EVENING | NIGHT
```

**Cette table ne filtre rien.** Une disponibilité déclarée est une habitude, pas un
engagement : qui a coché « mardi soir » peut vouloir un samedi matin, et masquer le reste lui
cacherait ce qu'il cherchait ce jour-là. La pondération vit dans l'`ORDER BY` du fil, jamais
dans son `WHERE`, et ne joue **qu'entre créneaux du même jour** — la chronologie n'est jamais
bousculée.

Aucun index supplémentaire : la requête entre toujours par utilisateur, et la clé primaire
suffit.

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
| **GIN** (trigrammes, V77) | `activities(name gin_trgm_ops)`, `programs(title gin_trgm_ops)` |
| **Uniques partiels** | `uq_sub_author`, `uq_sub_user_activity`, `uq_sub_category`, `uq_conversations_program_broadcast`, `slot_participations(schedule_id, waitlist_position) WHERE status='WAITLISTED'` |
| **Partiel de balayage** | `idx_schedules_reminder_sweep ON schedules(starts_at) WHERE status IN ('OPEN','FULL')`, `idx_messages_location_expires ON messages(location_expires_at) WHERE location_expires_at IS NOT NULL` |

---

## 22. Valeurs d'énumération

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
| `programs.created_via` *(V61)* | `FULL` `QUICK` | `FULL` |
| `users.onboarding_step` *(V60 · V74)* | `ACTIVITIES` `LEVELS` `LOCATION` `PREVIEW` | — |
| `schedules.primary_language` *(V71)* | `fr` `en` `de` — étiquette courte, minuscules | — |
| `user_languages.proficiency` *(V71)* | `NATIVE` `FLUENT` `CONVERSATIONAL` `BASIC` | — |
| `user_availability.time_slot` *(V73)* | `MORNING` `AFTERNOON` `EVENING` `NIGHT` | — |
| `schedule_accessibility_tags.tag` *(V72)* | `WHEELCHAIR_ACCESSIBLE` `NO_ALCOHOL` `FAMILY_FRIENDLY` `FREE_OF_CHARGE` `BEGINNER_WELCOME` … | — |
| `user_blocks.reason` *(V62)* | libre, facultatif — jamais montré au bloqué | — |
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

## 23. Divergences relevées entre la base et le code

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

## 24. Reproduire ce relevé

Deux voies, et la seconde ne demande aucune instance à part.

**Sur une base démarrée** — les requêtes qui ont servi aux deux relevés :

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

**Sans instance démarrée**, ce qui est le cas courant en développement : les mêmes requêtes
depuis un test d'intégration jetable étendant `AbstractIntegrationTest`, dont le conteneur
vient d'appliquer toutes les migrations. C'est la méthode utilisée pour le relevé du
20 août 2026 — un `JdbcTemplate`, les cinq requêtes ci-dessus, une écriture sur disque, puis
suppression du test. Elle a l'avantage de décrire ce que les migrations produisent
réellement, et non ce qu'une instance particulière a accumulé.

> Ce document se périme à chaque migration. Le refaire coûte dix minutes ; le déduire à la
> main des fichiers de migration coûte plus cher et se trompe — c'est ainsi que le relevé
> précédent est resté figé à `V59` pendant dix-huit migrations.

---

## Endpoints API

La liste des routes vivait auparavant dans ce fichier ; elle y était incomplète (97 routes
recensées pour **179** réellement exposées) et n'a pas sa place dans un document de schéma.
Elle est désormais tenue à jour, contrôleur par contrôleur, dans
[`ARCHITECTURE_BACKEND.md`](ARCHITECTURE_BACKEND.md) § 4, et la référence exécutable reste
`/swagger-ui.html` sur une instance démarrée.
