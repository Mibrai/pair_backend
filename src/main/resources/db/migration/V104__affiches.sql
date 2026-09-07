-- ============================================================
-- V104 — les affiches : publier ce qu'on a vécu sans être l'hôte
--
-- Une « affiche » est l'objet visuel qu'une personne compose après une séance
-- où elle était présente, découvre en privé, puis publie — ou non. Le visuel et
-- la phrase sont composés par le client à partir de ses propres cartes-souvenirs
-- et n'entrent PAS ici : cette table ne retient que le fait qu'une affiche
-- existe, laquelle, et pour qui.
--
-- 1. POURQUOI UNE TABLE, ET PAS UNE COLONNE SUR slot_recaps. La carte-souvenir
--    porte le moment COLLECTIF et n'a qu'un publieur, l'hôte
--    (PATCH /api/slots/{id}/recap/visibility rend 403 à tout autre). L'affiche
--    est individuelle : dix personnes présentes à la même séance en publient dix,
--    avec dix audiences. Le déclencheur n'est pas l'organisation, c'est
--    attendances.was_present.
--
-- 2. LA CLÉ PORTE LA SÉANCE, PAS LA LIGNE DE CRÉNEAU. C'est la correction que
--    slot_recaps a déjà dû subir (uq_recap_occurrence, et le commentaire de
--    SlotRecap qui la raconte) : sur une série hebdomadaire, une clé
--    (user_id, schedule_id) aurait réécrit d'une semaine sur l'autre l'affiche
--    d'un cours régulier — c'est-à-dire précisément le seul cas où quelqu'un en
--    produit plusieurs. occurrence_start nomme la séance, comme
--    attendances.attended_at, avec lequel il se joint directement.
--
-- 3. L'AUDIENCE EST UNE CONTRAINTE, PAS UNE CHAÎNE LIBRE. Le CHECK ci-dessous
--    est la moitié en base d'une règle dont l'autre moitié est dans
--    AfficheService : le réglage est APPLIQUÉ à la lecture, il n'est pas
--    seulement rangé. C'est ce qui sépare cette colonne de
--    user_preferences.value, dont le contrat dit que « le serveur range cette
--    valeur, il ne l'interprète pas ». Un réglage de confidentialité que rien
--    n'applique est une promesse non tenue.
--
-- 4. LE MOTIF EST OPAQUE, ET LE RESTE. Aucun CHECK ne l'énumère : le serveur ne
--    le lit jamais, et l'énumérer ferait dépendre l'ajout d'un quatorzième motif
--    d'un déploiement serveur. Sa FORME est contrainte côté application
--    (AfficheService.MOTIF), parce que la valeur ressort chez des tiers.
--
-- 5. occurrence_end EST COPIÉE, pas relue. La fenêtre de mise en avant de sept
--    jours en découle : sans cette copie, allonger un créneau des mois plus tard
--    remonterait une affiche sur un profil. Même raison que
--    slot_recaps.occurrence_end.
--
-- Aucun remplissage rétroactif : personne n'a jamais publié d'affiche, il n'y a
-- pas d'état antérieur à reconstituer.
-- ============================================================

CREATE TABLE IF NOT EXISTS affiches (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- L'auteur de l'affiche : celui qui y était, pas celui qui organisait.
    user_id          UUID        NOT NULL REFERENCES users(id)     ON DELETE CASCADE,
    schedule_id      UUID        NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,

    -- La séance, nommée par son début — la même valeur que attendances.attended_at.
    occurrence_start TIMESTAMPTZ NOT NULL,
    occurrence_end   TIMESTAMPTZ NOT NULL,

    -- Clé de motif choisie par le client. Jamais lue par le serveur.
    motif            VARCHAR(40) NOT NULL,

    audience         VARCHAR(20) NOT NULL DEFAULT 'NOBODY',

    -- Quand l'affiche est devenue visible sous son audience actuelle. Rafraîchie
    -- à chaque OUVERTURE de l'audience, et à elle seule : c'est cette date que
    -- GET /api/affiches/updates compare à `since`, donc elle qui allume l'anneau
    -- sur l'avatar. Un motif corrigé ne rallume rien.
    published_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_affiche_audience
        CHECK (audience IN ('NOBODY', 'SUBSCRIBERS', 'EVERYONE')),
    -- Une séance ne peut pas se terminer avant d'avoir commencé. Le garde-fou
    -- vaut surtout pour les séances anciennes, dont la fin est reconstruite par
    -- la durée du créneau (SlotTiming.occurrenceEndOf) et non lue.
    CONSTRAINT chk_affiche_occurrence CHECK (occurrence_end > occurrence_start)
);

-- « Une affiche par personne et par séance ». Unique, donc opposable : deux
-- publications concurrentes ne peuvent pas produire deux lignes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_affiche_user_occurrence
    ON affiches (user_id, schedule_id, occurrence_start);

-- La lecture d'un profil : les affiches de quelqu'un, de la plus récente à la
-- plus ancienne.
CREATE INDEX IF NOT EXISTS idx_affiches_user_published
    ON affiches (user_id, published_at DESC);

-- GET /api/affiches/updates. Partiel : une affiche que personne d'autre ne peut
-- voir n'a rien à faire dans l'index qui sert à baguer les avatars, et c'est le
-- réglage par défaut — sans ce WHERE, l'index grossirait surtout de lignes que
-- la requête écarte systématiquement.
CREATE INDEX IF NOT EXISTS idx_affiches_published
    ON affiches (published_at DESC)
    WHERE audience <> 'NOBODY';
