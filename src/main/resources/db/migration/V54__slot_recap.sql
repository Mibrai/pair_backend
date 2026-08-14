-- meetDo : la carte-souvenir d'un créneau.
--
-- Couche d'agrégation au-dessus de données déjà possédées (attendances,
-- schedules, activités) : la carte porte sur le MOMENT COLLECTIF, jamais sur
-- les individus. Aucune colonne de performance, de note ou de classement n'a
-- sa place ici — un champ absent du schéma ne peut pas être affiché par
-- erreur demain.

-- La carte elle-même : une par créneau, créée à la première contribution.
CREATE TABLE slot_recaps (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id        UUID NOT NULL UNIQUE REFERENCES schedules(id) ON DELETE CASCADE,
    visibility         VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    host_note          VARCHAR(400),
    -- Dénormalisation du nombre de présents confirmés, tenue comme
    -- schedules.participant_count : rafraîchie à chaque contribution et à
    -- chaque confirmation de présence. Jamais un score, jamais comparée.
    attendee_count     INTEGER NOT NULL DEFAULT 0,
    published_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recaps_schedule   ON slot_recaps(schedule_id);
CREATE INDEX idx_recaps_visibility ON slot_recaps(visibility, published_at DESC);

-- Les mots d'ambiance choisis par chaque participant. Vocabulaire fermé
-- (enum SlotVibe) : jamais de saisie libre, donc rien à modérer et une clé de
-- traduction par valeur côté client.
CREATE TABLE recap_vibe_votes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recap_id    UUID NOT NULL REFERENCES slot_recaps(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vibe        VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_vibe_vote UNIQUE (recap_id, user_id, vibe)
);

CREATE INDEX idx_vibe_recap ON recap_vibe_votes(recap_id);

-- Consentement à apparaître : opt-in explicite, jamais implicite. Un
-- participant sans ligne ici est compté dans le total mais jamais nommé.
CREATE TABLE recap_participant_consents (
    recap_id      UUID NOT NULL REFERENCES slot_recaps(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    show_identity BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (recap_id, user_id)
);

-- Souvenir photo, porté par l'attendance et non par une table dédiée : c'est
-- la personne présente qui possède sa photo, et c'est elle qui décide de la
-- rendre publique. Le fichier passe par le chemin d'upload existant
-- (POST /api/media/upload/image) — il n'y en a toujours qu'un seul.
ALTER TABLE attendances
    ADD COLUMN memory_photo_url VARCHAR(500),
    ADD COLUMN memory_is_public BOOLEAN NOT NULL DEFAULT FALSE;

-- Ville du créneau, pour la carte-souvenir : nom du lieu et ville toujours,
-- adresse exacte jamais (elle reste soumise à SlotAddressVisibility).
-- Nullable et jamais devinée : aucun service de géocodage réel n'est branché,
-- et une ville inventée vaut moins que pas de ville.
ALTER TABLE schedules
    ADD COLUMN city VARCHAR(120);
