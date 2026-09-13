-- P-BL-22 (décision du 13/09) — « Reporter » la veille a une fin.
--
-- Chaque report repousse l'heure limite de 30 minutes et réarme les rappels ; il
-- n'y avait ni compteur ni plafond. Au-delà de 3 reports (1 h 30) ou de 2 h
-- cumulées, le report est refusé et l'escalade suit son cours. Le compte sert
-- aussi au message au contact, qui dit combien de fois l'heure a été repoussée.
--
-- données : les reports déjà faits sont comptés depuis la chronologie, pour que
-- les veilles en cours ne regagnent pas trois reports au déploiement.

ALTER TABLE watches ADD COLUMN IF NOT EXISTS snooze_count INTEGER NOT NULL DEFAULT 0;

UPDATE watches w
   SET snooze_count = (SELECT count(*) FROM watch_events e
                        WHERE e.watch_id = w.id AND e.type = 'SNOOZED')
 WHERE EXISTS (SELECT 1 FROM watch_events e WHERE e.watch_id = w.id AND e.type = 'SNOOZED');
