-- P-BL-15 / P-BA-19 (décision du 13/09) — une séance a toujours une fin.
--
-- données : les créneaux sans ends_at (seeds, anciens clients, appels directs)
-- reçoivent starts_at + la durée déclarée du programme, et deux heures à défaut
-- — la convention que le serveur appliquait déjà en silence (SlotTiming). Même
-- règle pour la dernière occurrence retirée d'une série. L'écriture refuse
-- désormais un créneau sans fin (400) ; la contrainte NOT NULL viendra dans une
-- migration séparée, une fois les journaux confirmés.

UPDATE schedules s
   SET ends_at = s.starts_at + make_interval(mins => CASE WHEN p.session_duration_minutes > 0 THEN p.session_duration_minutes ELSE 120 END)
  FROM programs p
 WHERE p.id = s.program_id
   AND s.ends_at IS NULL;

UPDATE schedules s
   SET last_occurrence_end = s.last_occurrence_start
                             + make_interval(mins => CASE WHEN p.session_duration_minutes > 0 THEN p.session_duration_minutes ELSE 120 END)
  FROM programs p
 WHERE p.id = s.program_id
   AND s.last_occurrence_start IS NOT NULL
   AND s.last_occurrence_end IS NULL;
