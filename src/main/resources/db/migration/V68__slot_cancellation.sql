-- Annulation notifiée (lot C2 de meetdo-v2)
--
-- Il existait déjà une suppression de créneau : elle bascule le statut en
-- CANCELLED et prévient les inscrits, mais sans garder trace de quoi que ce
-- soit. Personne ne sait pourquoi, ni quand, ni qui.
--
-- Ces trois colonnes ne servent pas l'organisateur — il sait ce qu'il a fait —
-- mais les participants, qui reçoivent un motif plutôt qu'un fait brut, et la
-- modération, qui peut voir qu'un créneau a été annulé trois heures avant.

ALTER TABLE schedules
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(300),
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ,

    -- ON DELETE SET NULL : la suppression d'un compte ne doit pas effacer le
    -- fait qu'une séance a été annulée, ni emporter la ligne du créneau.
    ADD COLUMN IF NOT EXISTS cancelled_by UUID REFERENCES users(id) ON DELETE SET NULL;
