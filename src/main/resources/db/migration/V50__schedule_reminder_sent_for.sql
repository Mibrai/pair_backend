-- Rappel T-2h (N6) — mémoire d'idempotence du job.
--
-- La colonne ne dit pas « le rappel est parti » mais « le rappel est parti POUR
-- CET INSTANT DE DÉBUT ». La nuance porte toute la logique de replanification :
--
--   * créneau déplacé  → starts_at change, reminder_sent_for ne correspond plus,
--                        le créneau est rebalayé et un nouveau rappel part ;
--   * créneau annulé   → status passe à CANCELLED, le balayage l'ignore ;
--   * créneau inchangé → les deux valeurs coïncident, rien ne repart.
--
-- Un booléen aurait exigé de le remettre à zéro depuis chaque chemin qui déplace
-- un créneau (ProgramService.updateSchedule, RecurringSlotRolloverJob, un import
-- à venir) — trois endroits à ne jamais oublier. Ici il n'y en a aucun : la
-- comparaison suffit, et un chemin de déplacement qu'on n'aurait pas vu se
-- comporte correctement sans le savoir.
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS reminder_sent_for TIMESTAMPTZ;

COMMENT ON COLUMN schedules.reminder_sent_for IS
  'starts_at pour lequel le PROGRAM_REMINDER T-2h a été émis. NULL = jamais émis. '
  'Différent de starts_at = créneau déplacé depuis, un nouveau rappel est dû.';

-- Le job balaie une fenêtre de deux heures toutes les cinq minutes : sans index,
-- c'est un seq scan de toute la table à chaque passage.
CREATE INDEX IF NOT EXISTS idx_schedules_reminder_sweep
  ON schedules (starts_at)
  WHERE status IN ('OPEN', 'FULL');

-- Les créneaux déjà commencés ou passés à la livraison ne doivent pas déclencher
-- une salve de rappels rétroactifs au premier démarrage. On les marque comme
-- déjà traités — leur rappel, s'il avait lieu d'être, est de toute façon manqué.
UPDATE schedules
   SET reminder_sent_for = starts_at
 WHERE starts_at <= NOW() + INTERVAL '2 hours';
