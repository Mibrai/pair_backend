-- Relance de présence (P-BL-07) — mémoire d'idempotence du job horaire.
--
-- Même patron que reminder_sent_for (V50), et pour la même raison : la colonne
-- ne dit pas « la relance est partie » mais « la relance est partie POUR CETTE
-- SÉANCE-LÀ ». Le job balaie les fins entre H-3 et H-1 toutes les heures : une
-- fenêtre de deux heures parcourue chaque heure retient la même séance lors de
-- deux passages, et sans cette colonne un non-répondant recevait deux fois la
-- même question.
--
-- La valeur inscrite est le DÉBUT de l'occurrence relancée, jamais starts_at de
-- la ligne — les deux ne coïncident que pour une séance unique. Sur un créneau
-- récurrent, RecurringSlotRolloverJob a déjà avancé la ligne quand la relance
-- part : comparer à starts_at marquerait la séance suivante comme déjà relancée
-- et la série n'en recevrait plus jamais aucune. L'occurrence est identifiée par
-- son début, comme dans attendances (uq_attendance, V57) et slot_recaps.
--
-- Un booléen aurait été faux pour la même raison : il aurait fallu le remettre à
-- zéro à chaque rollover, donc dans un chemin qui n'a aucune raison de connaître
-- la relance de présence. Ici la comparaison suffit, et une nouvelle occurrence
-- redevient éligible sans que rien ne l'annonce.
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS attendance_prompted_for TIMESTAMPTZ;

COMMENT ON COLUMN schedules.attendance_prompted_for IS
  'Début de l''occurrence pour laquelle la relance ATTENDANCE_PROMPT a été émise. '
  'NULL = aucune relance émise. Jamais starts_at de la ligne sur un créneau '
  'récurrent : le rollover l''a déjà avancée quand la relance part.';

-- Pas de rattrapage, et c'est délibéré : aucune valeur n'est posée ici. La
-- première heure après le déploiement, les séances terminées dans la fenêtre
-- H-3/H-1 recevront donc leur relance — celle qu'elles auraient dû recevoir —
-- une fois, marqueur vide. C'est le comportement voulu, et l'inverse (marquer
-- tout l'existant) priverait de leur question ceux qui viennent de terminer une
-- séance au moment de la livraison.
--
-- L'index de balayage, en revanche, est nécessaire : la requête du job ajoute
-- désormais une branche sur last_occurrence_end pour les séries, et sans index
-- elle parcourt toute la table chaque heure.
CREATE INDEX IF NOT EXISTS idx_schedules_last_occurrence_end
  ON schedules (last_occurrence_end)
  WHERE recurrence_rule IS NOT NULL;
