-- Partage de sécurité d'un créneau (lot A4 de meetdo-v2)
--
-- « Je vais voir quelqu'un que je ne connais pas, samedi, là-bas. » Un lien
-- temporaire, lisible sans compte, que l'on envoie à un proche.
--
-- La table ne stocke aucune donnée de la page : tout est relu depuis le créneau
-- au moment de l'affichage, sauf ce qui doit rester figé — voir plus bas.

CREATE TABLE IF NOT EXISTS slot_safety_shares (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,

    -- Jeton opaque, base62 sur 22 caractères, jamais l'UUID du créneau : celui-ci
    -- sert d'identifiant partout ailleurs, et le publier offrirait une prise à
    -- l'énumération. Généré par ShareToken, comme celui de la page publique.
    share_token  VARCHAR(22) NOT NULL UNIQUE,

    -- Figé à la création, six heures après la fin prévue de la séance partagée.
    -- Recalculer cette date à la lecture depuis schedules serait une erreur :
    -- RecurringSlotRolloverJob avance starts_at et ends_at toutes les dix minutes
    -- sur un créneau récurrent, si bien que l'échéance fuirait devant nous et que
    -- le lien ne périmerait jamais.
    expires_at   TIMESTAMPTZ NOT NULL,

    -- Pour la même raison, la séance partagée est figée elle aussi. Sans ces deux
    -- colonnes, un lien envoyé pour la séance de samedi afficherait, après le
    -- premier rollover, la date du samedi suivant : le proche croirait s'inquiéter
    -- pour un rendez-vous qui n'a plus lieu ce jour-là. La spécification ne les
    -- demandait pas ; elle décrivait une table pour des séances uniques.
    occurrence_starts_at TIMESTAMPTZ NOT NULL,
    occurrence_ends_at   TIMESTAMPTZ NOT NULL,

    -- Renseigné à la première ouverture. Sert à dire « votre proche a bien vu le
    -- lien », jamais à compter les consultations.
    viewed_at    TIMESTAMPTZ,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Le jeton est le seul chemin d'accès à la page : c'est lui qu'on interroge, à
-- chaque ouverture, et jamais l'identifiant du créneau.
CREATE INDEX IF NOT EXISTS idx_safety_token ON slot_safety_shares(share_token);
