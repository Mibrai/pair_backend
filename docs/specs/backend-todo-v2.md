# meetDo v2 — TODO backend

> Extrait de `meetdo-v2-specification.md` (18 août 2026). **Ce fichier ne
> contient que le travail serveur.** Le travail Flutter est dans
> `frontend-plan-v2.md`.
>
> Base de départ : Spring Boot 4.1.0 (`org.program.pair`), PostgreSQL 16 +
> PostGIS + pgvector, Flyway **V59**, 179 endpoints, 81 classes de test.
>
> ⚠️ **Avant de créer un fichier de migration**, lister `db/migration/` : la
> numérotation ci-dessous suppose que la base est restée à V59. Si d'autres
> migrations ont été ajoutées entre-temps, décaler toute la séquence sans
> changer l'ordre relatif.

---

## Règles transverses à appliquer à chaque endpoint ajouté

- [ ] **`404`, jamais `403`**, sur toute ressource inaccessible — profil
      bloqué, créneau non partageable, token expiré. Un `403` révèle
      l'existence de la ressource.
- [ ] **Aucune donnée de tiers sur une surface publique** : jamais d'e-mail,
      de téléphone, d'UUID utilisateur, d'adresse exacte d'un lieu privé, ni
      de liste de participants.
- [ ] **Aucun endpoint de tri ou de filtre** sur un compteur de fiabilité,
      de présence ou d'invitation — ce serait un classement entre personnes.
- [ ] **Token opaque, jamais l'UUID interne**, dans une URL publique
      (base62, 22 caractères, généré à la création — protection contre
      l'énumération).
- [ ] Tout nouveau champ d'énumération renvoyé au client doit rester lisible
      par un client ancien : le front parse en tolérant l'inconnu, le
      serveur ne doit pas pour autant renommer un existant.

---

# PHASE A — utilisable et publiable (V60 → V64)

## A1 · Onboarding guidé — V60

- [ ] `V60__onboarding_state.sql`
      ```sql
      ALTER TABLE users
          ADD COLUMN onboarding_completed_at TIMESTAMPTZ,
          ADD COLUMN onboarding_step VARCHAR(30);
      ```
- [ ] `GET   /api/users/me/onboarding` — état courant (étape, complété ou non).
- [ ] `PATCH /api/users/me/onboarding` — avancer d'une étape (idempotent :
      rejouer la même étape ne doit pas produire d'erreur, le réseau mobile
      double les requêtes).
- [ ] `POST  /api/users/me/onboarding/skip` — passer, **autorisé**, mais tracé.
- [ ] `GET /api/activities/suggested?lat=&lng=&limit=12`
      - activités les plus représentées autour de la position ;
      - pondérées pour couvrir **au moins 4 catégories différentes** ;
      - zone vide → repli sur les plus populaires globalement ;
      - **ne jamais renvoyer une liste vide** (garde-fou n°6).
- [ ] Exposer `onboardingCompletedAt` / `onboardingStep` dans le DTO de
      `GET /api/users/me` — le front en a besoin au démarrage pour décider
      de la redirection, et un second appel réseau au lancement se voit.

> **Attendu du front (A1) :** l'écran 4 de l'onboarding affiche des données
> réelles. Il consomme `/api/slots/feed` et/ou `/api/activities/suggested`
> autour de la position tout juste autorisée. Ces deux routes doivent
> répondre correctement **avec un utilisateur qui n'a encore aucune
> activité déclarée** — c'est exactement le cas qui n'existait pas avant.

## A2 · Chemin court « je cherche quelqu'un pour… » — V61

- [ ] `V61__quick_slot_flag.sql`
      ```sql
      ALTER TABLE programs ADD COLUMN created_via VARCHAR(20) NOT NULL DEFAULT 'FULL';
      -- 'FULL' | 'QUICK'
      ```
- [ ] `POST /api/quick-slots`
      ```java
      public record QuickSlotRequest(
          @NotNull UUID activityId,        // ou activityQuery, résolution libre
          @NotNull Instant startsAt,
          Instant endsAt,                  // défaut : +2 h
          @NotBlank String placeName,
          @NotNull PlaceType placeType,    // PUBLIC / PRIVATE / ONLINE
          Double lat, Double lng,
          String addressPublic,
          Boolean showExactAddress,        // défaut false
          Integer maxParticipants,         // null = sans limite
          @Size(max = 300) String welcomeNote,
          ActivityLevel level,             // défaut ANY
          ActivityFormat format            // défaut ANY
      ) {}
      ```
- [ ] **Une seule transaction**, aucune entité nouvelle :
      1. `user_activity` créée si absente ;
      2. `program` au titre auto-généré (activité + date), `created_via='QUICK'` ;
      3. `schedule` correspondant, `is_open_to_partners = true`.
- [ ] Réponse : le `schedule` créé, sous le **même DTO** que
      `POST /api/slots` — sinon le front doit maintenir deux modèles pour un
      seul objet.
- [ ] Exposer `createdVia` sur le DTO de programme (permettra plus tard le
      « transformer en programme complet »).

> ⚠️ **Rappel du terrain** : `addressPublic` est requis en pratique alors que
> la spec OpenAPI le dit optionnel, et les `lat`/`lng` sont revenus à `0,0`
> par le passé. Vérifier les deux sur cette nouvelle route avant de la
> déclarer prête.

## A3 · Blocage d'utilisateur — V62 · **bloquant pour les stores**

- [ ] `V62__user_blocks.sql`
      ```sql
      CREATE TABLE user_blocks (
          id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          blocker_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          blocked_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          reason       VARCHAR(30),
          created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT uq_user_block UNIQUE (blocker_id, blocked_id),
          CONSTRAINT chk_no_self_block CHECK (blocker_id <> blocked_id)
      );
      CREATE INDEX idx_blocks_blocker ON user_blocks(blocker_id);
      CREATE INDEX idx_blocks_blocked ON user_blocks(blocked_id);
      ```
- [ ] `POST   /api/users/{userId}/block`
- [ ] `DELETE /api/users/{userId}/block`
- [ ] `GET    /api/users/me/blocked` — paginé (enveloppe `Page<T>` comme
      `/notifications`).
- [ ] `BlockFilterService` **central**, bilatéral, appliqué sur **toutes** les
      surfaces ci-dessous. C'est le point le plus facile à rater du document :

| Surface | Comportement attendu | ✓ |
|---|---|---|
| `/api/map/users`, `/clusters`, `/bounds` | ni l'un ni l'autre ne se voient | ☐ |
| `/api/slots/feed`, `/api/recaps/feed` | créneaux et recaps mutuellement masqués | ☐ |
| `/api/search` | exclus des résultats | ☐ |
| `/api/conversations` | conversation existante masquée **des deux côtés** | ☐ |
| `POST /api/conversations` | refus `USER_BLOCKED` | ☐ |
| `POST /api/slots/{id}/join` | refus si l'organisateur a bloqué le demandeur | ☐ |
| Notifications | aucune notification produite par un utilisateur bloqué | ☐ |
| `/api/users/{id}` | profil inaccessible — **`404`, jamais `403`** | ☐ |
| Abonnements | rompus automatiquement **dans les deux sens** | ☐ |

- [ ] Ajouter `USER_BLOCKED` à l'énumération `ErrorCode`.
- [ ] Le blocage ne doit **pas** être détectable par la personne bloquée :
      pas de notification, pas de code d'erreur distinctif côté bloqué.

## A4 · Sécurité des rencontres physiques — V63

- [ ] `V63__slot_safety_share.sql`
      ```sql
      CREATE TABLE slot_safety_shares (
          id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
          share_token  VARCHAR(22) NOT NULL UNIQUE,
          expires_at   TIMESTAMPTZ NOT NULL,
          viewed_at    TIMESTAMPTZ,
          created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE INDEX idx_safety_token ON slot_safety_shares(share_token);
      ```
- [ ] `POST /api/slots/{scheduleId}/safety-share` → lien temporaire
      (réservé à un participant inscrit ou à l'organisateur).
- [ ] `GET /public/safety/{token}` → page publique, **sans compte**.
- [ ] Contenu affiché, **et rien d'autre** : activité, date et heure, nom du
      lieu et ville, **prénom** de l'organisateur, heure de fin prévue.
- [ ] Expiration automatique **6 h après la fin du créneau**.
- [ ] ⚠️ Ne jamais inclure : adresse exacte, téléphone, e-mail, identifiants
      internes, liste des autres participants.

## A5 · Règles de communauté — V64 · **bloquant pour les stores**

- [ ] `V64__community_guidelines_acceptance.sql`
      ```sql
      ALTER TABLE users
          ADD COLUMN guidelines_accepted_at TIMESTAMPTZ,
          ADD COLUMN guidelines_version VARCHAR(10);
      ```
- [ ] Endpoint d'acceptation + exposition de la version courante attendue,
      pour pouvoir redemander l'acceptation après une modification
      substantielle.
- [ ] Le texte des règles est servi côté serveur **ou** embarqué côté client —
      trancher avec le front avant de commencer (voir « contrats à trancher »).

---

# PHASE B — acquisition (V65 → V66)

## B1 · Pages publiques de créneau partageables — V65

- [ ] `V65__public_slot_sharing.sql`
      ```sql
      ALTER TABLE schedules
          ADD COLUMN public_share_token VARCHAR(22) UNIQUE,
          ADD COLUMN is_publicly_shareable BOOLEAN NOT NULL DEFAULT TRUE,
          ADD COLUMN public_view_count INTEGER NOT NULL DEFAULT 0;
      CREATE INDEX idx_schedules_share_token ON schedules(public_share_token);
      ```
- [ ] Token base62 de 22 caractères, généré à la création. **Jamais l'UUID.**
- [ ] Backfill des créneaux existants avec un token.
- [ ] `GET /public/slots/{token}` → JSON.
- [ ] `GET /public/slots/{token}/page` → HTML + **métadonnées OpenGraph**.
- [ ] DTO public strictement filtré — **autorisé** : titre, activité,
      catégorie, date, nom du lieu, ville, nombre de participants, mot
      d'accueil, prénom et avatar de l'organisateur, badge vérifié, image.
      **Interdit** : e-mail, téléphone, UUID utilisateur, coordonnées exactes
      d'un lieu privé, liste des participants.
- [ ] `404` (jamais `403`) si : non partageable, organisateur inactif,
      programme non public, activité masquée, ou créneau passé de plus de 24 h.
- [ ] **Métadonnées OpenGraph — le point décisif.** Sans elles, un lien collé
      dans WhatsApp reste une URL nue et ne convertit pas.
      ```html
      <meta property="og:title"       th:content="${slot.programTitle}">
      <meta property="og:description" th:content="${ogDescription}">
      <meta property="og:image"       th:content="${slot.imageUrl}">
      <meta name="twitter:card"       content="summary_large_image">
      ```
      Description générée concrète :
      `« Samedi 14 juin, 9h · Yoga · Studio Lumière, Strasbourg · 3 inscrits »`.
- [ ] Page minimale : visuel, informations essentielles, **un seul bouton**
      (« Rejoindre sur meetDo »), deep link vers l'app, repli vers le store.
      Aucun formulaire.
- [ ] **Fichiers de vérification des liens universels** — sans eux le deep
      link `https://` du front ne rouvrira jamais l'app :
      - `/.well-known/apple-app-site-association` (servi en
        `application/json`, **sans redirection**) ;
      - `/.well-known/assetlinks.json`.
- [ ] Route courte `https://<domaine>/s/{token}`.

## B2 · Invitation nominative — V66

- [ ] `V66__slot_invitations.sql`
      ```sql
      CREATE TABLE slot_invitations (
          id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          inviter_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          schedule_id   UUID REFERENCES schedules(id) ON DELETE SET NULL,
          invite_code   VARCHAR(16) NOT NULL UNIQUE,
          invitee_id    UUID REFERENCES users(id),
          created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          joined_at     TIMESTAMPTZ,
          converted_at  TIMESTAMPTZ
      );
      ```
- [ ] `POST /api/slots/{scheduleId}/invite` → lien traçable.
- [ ] `GET  /api/invitations/me` → mes invitations et leur statut.
- [ ] Récompense : **un badge de catégorie `ROLE`** après une première
      invitation convertie. **Pas de points, pas de classement de parrains,
      pas de récompense monétaire** (garde-fou n°1).

## B3 · Export calendrier (ICS)

- [ ] `ical4j` est **déjà présent** (`RecurrenceExpander` l'utilise) — pas de
      nouvelle dépendance à ajouter, vérifier simplement la version 4.0.4.
- [ ] `GET /api/slots/{scheduleId}/calendar.ics` — participant ou organisateur.
- [ ] `GET /api/slots/mine/calendar.ics` — tous mes créneaux à venir.
- [ ] `GET /public/slots/{token}/calendar.ics` — public si partageable.
- [ ] Contenu : titre, début/fin, nom du lieu, description avec lien vers la
      page publique, **alarme de rappel 2 h avant**.
- [ ] ⚠️ Filtrage identique à B1 : jamais d'adresse exacte d'un lieu privé
      non partagé dans `LOCATION`.

---

# PHASE C — qualité de la rencontre (V67 → V69)

## C1 · Liste d'attente — V67

- [ ] `V67__slot_waitlist.sql`
      ```sql
      ALTER TABLE slot_participations
          ADD COLUMN waitlist_position INTEGER,
          ADD COLUMN promoted_at TIMESTAMPTZ;
      ```
- [ ] Ajouter `WAITLISTED` à `ParticipationStatus`.
- [ ] `POST   /api/slots/{sid}/waitlist`
- [ ] `DELETE /api/slots/{sid}/waitlist`
- [ ] `GET    /api/slots/{sid}/waitlist` — **organisateur uniquement**.
- [ ] **Promotion automatique** au désistement d'un confirmé : le premier de
      la file passe `CONFIRMED`, ordre strictement chronologique, notification
      de type **`WAITLIST_PROMOTED`**.
- [ ] Course concurrente : deux désistements simultanés ne doivent pas
      promouvoir deux fois la même personne ni sauter un rang (verrou sur la
      ligne du schedule).
- [ ] Exposer la position de l'appelant dans le DTO de créneau — le front
      affiche « 2ᵉ sur la liste ».

## C2 · Annulation notifiée — V68

- [ ] `V68__slot_cancellation.sql`
      ```sql
      ALTER TABLE schedules
          ADD COLUMN cancellation_reason VARCHAR(300),
          ADD COLUMN cancelled_at TIMESTAMPTZ,
          ADD COLUMN cancelled_by UUID REFERENCES users(id);
      ```
- [ ] `POST /api/slots/{sid}/cancel` — corps `{ reason }`.
- [ ] Notifie **immédiatement** tous les participants confirmés **et** en
      liste d'attente. Nouveau type **`SLOT_CANCELLED`**, canal **push et
      e-mail** — l'un des rares cas où le double canal se justifie.
- [ ] La charge utile porte de quoi proposer un repli : autres créneaux de la
      même activité à proximité (garde-fou n°6).
- [ ] ⚠️ Une annulation reste envoyée **même pendant les heures de silence**
      (voir D6) : c'est de l'information indispensable, pas de l'engagement.

## C3 · Signal de fiabilité — V69

- [ ] `V69__reliability_signal.sql`
      ```sql
      ALTER TABLE users
          ADD COLUMN joined_slots_count INTEGER NOT NULL DEFAULT 0,
          ADD COLUMN confirmed_attendance_count INTEGER NOT NULL DEFAULT 0;
      ```
- [ ] ⚠️ **Traitement impératif, sous peine de trahir l'identité du produit :**
      - **jamais de pourcentage**, jamais de score, jamais de comparaison ;
      - un **libellé qualitatif** seulement, et seulement au-dessus du seuil
        de données suffisant (**≥ 5 créneaux rejoints**) :
        *« Vient habituellement quand il s'inscrit »* ;
      - **aucun libellé négatif** — l'absence de signal n'est pas un mauvais
        signal ;
      - **aucun endpoint de tri ou de filtrage** sur ces compteurs.
- [ ] Décision à prendre : le serveur renvoie-t-il le **libellé** (recommandé,
      garantit qu'aucun client ne peut recalculer un pourcentage) ou les deux
      compteurs bruts ? → voir « contrats à trancher ».

## C4 · No-show et désistement tardif

- [ ] Distinguer, dans la confirmation de présence existante, le participant
      qui n'est pas venu **et l'a signalé** de celui qui n'a rien dit.
- [ ] `AttendancePromptJob` : après la fenêtre de 7 jours, une non-réponse
      compte comme **« non confirmé »**, jamais comme une absence avérée.
- [ ] Alimente C3 sans jamais produire de sanction visible.

---

# PHASE D — profondeur (V70 → V74)

## D1 · Langues parlées — V70

- [ ] `V70__user_languages.sql`
      ```sql
      CREATE TABLE user_languages (
          user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          language    VARCHAR(5) NOT NULL,   -- fr, en, de, es, it…
          proficiency VARCHAR(20) NOT NULL,  -- NATIVE|FLUENT|CONVERSATIONAL|BASIC
          PRIMARY KEY (user_id, language)
      );
      CREATE INDEX idx_user_languages_lang ON user_languages(language);
      ```
- [ ] CRUD des langues sur `/api/users/me`.
- [ ] Filtre optionnel `languages` sur `/api/slots/feed`, `/api/search`,
      `/api/map/users`.
- [ ] Langue principale du créneau :
      `ALTER TABLE schedules ADD COLUMN primary_language VARCHAR(5);`
      → exposée sur le DTO de créneau (affichée sur la carte de créneau).

## D2 · Filtres d'accessibilité — V71

- [ ] `V71__slot_accessibility.sql`
      ```sql
      CREATE TABLE schedule_accessibility_tags (
          schedule_id UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
          tag         VARCHAR(40) NOT NULL,
          PRIMARY KEY (schedule_id, tag)
      );
      ```
- [ ] ```java
      public enum AccessibilityTag {
          WHEELCHAIR_ACCESSIBLE, FAMILY_FRIENDLY, BEGINNER_WELCOME,
          WOMEN_ONLY, QUIET_ENVIRONMENT, NO_ALCOHOL,
          PUBLIC_TRANSPORT_NEARBY, FREE_OF_CHARGE
      }
      ```
- [ ] Filtre sur le feed et la recherche.
- [ ] ⚠️ Tags **déclarés par l'organisateur, jamais vérifiés** — le contrat
      d'API doit le dire, l'UI l'affichera comme déclaratif.

## D3 · Disponibilités habituelles — V72

- [ ] `V72__user_availability.sql`
      ```sql
      CREATE TABLE user_availability (
          user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          day_of_week SMALLINT NOT NULL,   -- 1 = lundi … 7 = dimanche
          time_slot   VARCHAR(20) NOT NULL, -- MORNING | AFTERNOON | EVENING
          PRIMARY KEY (user_id, day_of_week, time_slot)
      );
      ```
- [ ] Sert à **pondérer** les résultats du fil et de la recherche —
      **jamais à exclure**.

## D4 · Aperçu du profil public

- [ ] `GET /api/users/me/preview` → le **DTO exact** qu'un tiers recevrait.
- [ ] Réutiliser **strictement le même code de filtrage** que
      `/api/users/{id}` — un aperçu divergent serait pire que pas d'aperçu.
      Test dédié comparant les deux sorties.

## D5 · Confort de messagerie

- [ ] Indicateur de saisie : `/app/chat.typing` était prévu à l'origine,
      absent des routes actuelles — le rétablir.
- [ ] Partage de position **ponctuel** : explicite, unique, **expirant à
      30 minutes maximum**. Jamais un suivi continu (garde-fou n°4).
- [ ] Sourdine / archivage **par conversation**.

## D6 · Heures de silence — V73

- [ ] `V73__quiet_hours.sql`
      ```sql
      ALTER TABLE users
          ADD COLUMN quiet_hours_start SMALLINT,  -- 0-23, null = désactivé
          ADD COLUMN quiet_hours_end SMALLINT;
      ```
- [ ] `device_tokens` porte déjà `timezone` — s'en servir, ne pas redemander.
- [ ] Les notifications **transactionnelles critiques** (annulation imminente)
      restent envoyées. Les heures de silence concernent l'engagement, pas
      l'information indispensable.

## D7 · Tolérance aux fautes de frappe — V74

- [ ] `V74__trigram_search.sql`
      ```sql
      CREATE EXTENSION IF NOT EXISTS pg_trgm;
      CREATE INDEX idx_activities_name_trgm ON activities USING gin (name gin_trgm_ops);
      CREATE INDEX idx_programs_title_trgm  ON programs   USING gin (title gin_trgm_ops);
      ```
- [ ] **Quatrième couche** de `SearchService`, **en repli uniquement** : si le
      plein texte, le sémantique et la taxonomie ne donnent rien, similarité
      trigramme (seuil ~0,3). Aucun service supplémentaire à héberger.

## D8 · Filtres serveur sur l'Explorer

- [ ] Porter les trois filtres aujourd'hui appliqués **côté client sur les
      pages déjà chargées** (niveaux, « Mes activités », « Mes abonnements »)
      en **paramètres de requête** sur `GET /api/activities/browse`.
- [ ] Rétablir les **compteurs** correspondants.
- [ ] C'est aujourd'hui vécu comme un bug par les utilisateurs, pas comme une
      limite technique.

---

# Contrats à trancher avec le front avant de coder

Ces cinq points changent le code des deux côtés. Les décider maintenant coûte
une conversation ; les découvrir en intégration coûte deux réécritures.

1. **A1 — `GET /api/users/me`** porte-t-il l'état d'onboarding ? Le front en a
   besoin **au démarrage** pour décider où atterrir ; un second appel au
   lancement se voit à l'œil nu. → *recommandé : oui.*
2. **A2 — la réponse de `POST /api/quick-slots`** est-elle le **même DTO** que
   `POST /api/slots` ? → *recommandé : oui*, sinon le front maintient deux
   modèles pour un seul objet.
3. **A5 — le texte des règles de communauté** est-il servi par l'API ou
   embarqué dans l'app (comme `legal_terms_content.dart` aujourd'hui) ?
   Le versionnage impose au minimum que **la version attendue** vienne du
   serveur.
4. **C3 — le signal de fiabilité** : le serveur renvoie-t-il le **libellé
   qualitatif** ou les deux compteurs bruts ? → *recommandé : le libellé*.
   Renvoyer les compteurs, c'est laisser n'importe quel client afficher un
   pourcentage — exactement ce que le garde-fou n°1 interdit.
5. **B1 — le domaine public** et les fichiers `apple-app-site-association` /
   `assetlinks.json`. Sans eux, le lien `https://` du front n'ouvre jamais
   l'app. Le front ne peut pas les fournir, et ne peut pas tester sans.

---

# Ordre d'implémentation et jalons

```
PHASE A — utilisable et publiable          V60 → V64
  A1  Onboarding                            ← meilleur rapport effort/impact
  A2  Chemin court
  A3  Blocage                               ← bloquant stores
  A4  Sécurité rencontres
  A5  Règles de communauté                  ← bloquant stores

PHASE B — acquisition                      V65 → V66
  B1  Pages publiques + OpenGraph           ← seul canal gratuit
  B2  Invitation nominative
  B3  Export ICS                            ← agit sur la présence réelle

PHASE C — qualité                          V67 → V69
  C1 liste d'attente · C2 annulation · C3 fiabilité · C4 no-show

PHASE D — profondeur                       V70 → V74
  D1 langues · D2 accessibilité · D3 dispos · D4 aperçu · D5 messagerie
  D6 heures de silence · D7 fautes de frappe · D8 filtres Explorer
```

---

# Contrôles qualité backend

```markdown
- [ ] Le blocage est appliqué sur les NEUF surfaces listées en A3
- [ ] Tous les endpoints publics retournent 404, jamais 403
- [ ] Aucune surface publique n'expose adresse privée, e-mail, téléphone,
      UUID utilisateur ou liste de participants
- [ ] Aucun endpoint ne trie ni ne filtre sur un compteur de fiabilité
- [ ] Le partage de position expire à 30 min maximum
- [ ] Les tags d'accessibilité sont documentés comme déclaratifs
- [ ] /api/activities/suggested ne renvoie jamais une liste vide
- [ ] /api/users/me/preview produit exactement la sortie de /api/users/{id}
- [ ] La promotion de liste d'attente est correcte sous désistements
      concurrents (test dédié)
- [ ] Les annulations passent outre les heures de silence
- [ ] La séquence Flyway est contiguë et sans trou après vérification de
      db/migration/
```
