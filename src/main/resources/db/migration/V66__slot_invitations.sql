-- Invitation nominative (lot B2 de meetdo-v2)
--
-- Inviter quelqu'un sur un créneau précis, et savoir si l'invitation a abouti.
-- C'est la seule mesure d'acquisition du produit qui ne passe pas par un lien
-- anonyme : ici, on sait qui a invité qui.

CREATE TABLE IF NOT EXISTS slot_invitations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inviter_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- ON DELETE SET NULL et non CASCADE : un créneau supprimé ne doit pas
    -- effacer la trace d'une invitation qui a fait venir quelqu'un. La personne
    -- est là ; c'est le rendez-vous qui n'a pas eu lieu.
    schedule_id   UUID REFERENCES schedules(id) ON DELETE SET NULL,

    -- Même longueur et même générateur que les autres jetons du produit
    -- (ShareToken, base62 sur 22 caractères), là où la spécification prévoyait
    -- VARCHAR(16). Ce code voyage dans une URL et n'est jamais saisi à la main :
    -- rien ne justifiait une seconde forme de jeton, et deux formes finissent
    -- toujours par diverger sur la longueur d'une colonne.
    invite_code   VARCHAR(22) NOT NULL UNIQUE,

    invitee_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Renseigné quand l'invitation a fait venir un *nouveau* membre : le compte
    -- de l'invité est postérieur à l'invitation. Nul quand elle a été acceptée
    -- par quelqu'un qui était déjà là — l'invitation a marché, mais elle n'a
    -- recruté personne, et confondre les deux fausserait toute mesure.
    joined_at     TIMESTAMPTZ,

    -- Renseigné quand l'invité a effectivement rejoint le créneau. C'est ce que
    -- « invitation convertie » veut dire, et ce qui déclenche le badge.
    converted_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_invitations_inviter ON slot_invitations(inviter_id);

-- Le premier badge de catégorie ROLE du produit. La catégorie existait déjà dans
-- l'énumération sans qu'aucune ligne ne l'utilise, et la colonne n'a pas de
-- contrainte CHECK : l'insertion suffit.
--
-- Récompense volontairement pauvre : un badge, pas des points, pas de classement
-- de parrains, pas de récompense monétaire. Le garde-fou est explicite dans la
-- spécification, et il tient à ceci — dès qu'inviter rapporte quelque chose de
-- quantifiable, inviter devient un objectif, et les invitations cessent d'être
-- des invitations.
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES ('E1000000-0000-0000-0000-000000000101', 'HOST_INVITER', 'ROLE',
        'A fait venir quelqu''un', 'INVITATION_CONVERTED', 1, 'group_add')
ON CONFLICT (code) DO NOTHING;
