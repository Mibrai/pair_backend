-- No-show et désistement tardif (lot C4 de meetdo-v2)
--
-- La spécification ne prévoyait pas de migration pour ce lot. Elle en demande
-- une sans le dire : « après la fenêtre de sept jours, une non-réponse compte
-- comme non confirmé, jamais comme une absence avérée ». Or aujourd'hui une
-- non-réponse ne se distingue pas d'une question jamais posée — les deux se
-- lisent comme l'absence d'une ligne dans attendances.
--
-- Cette colonne matérialise la fermeture de la fenêtre. Elle ne dit pas que la
-- personne était absente ; elle dit que le moment de répondre est passé.
--
-- La numérotation de la phase D se décale donc d'un rang : D1 prendra V71.

ALTER TABLE slot_participations
    ADD COLUMN IF NOT EXISTS attendance_closed_at TIMESTAMPTZ;

-- Le job balaie des créneaux vieux de sept jours ; sans index, il trierait toute
-- la table à chaque passage horaire.
CREATE INDEX IF NOT EXISTS idx_slotpart_attendance_open
    ON slot_participations (schedule_id)
    WHERE status = 'CONFIRMED' AND attendance_closed_at IS NULL;
