-- P-BL-15 étape 3, second temps (décision du 13/09) — la fin d'une séance est
-- une contrainte de la base, plus seulement de l'API.
--
-- V119 a renseigné les anciennes lignes, et relevé du 13/09 en production :
-- aucune ligne sans ends_at après V119. Toute écriture par l'API exige une fin
-- depuis la même livraison. Retour arrière : ALTER COLUMN ends_at DROP NOT NULL.

UPDATE schedules s
   SET ends_at = s.starts_at + make_interval(mins => CASE WHEN p.session_duration_minutes > 0
                                                          THEN p.session_duration_minutes ELSE 120 END)
  FROM programs p
 WHERE p.id = s.program_id
   AND s.ends_at IS NULL;

ALTER TABLE schedules ALTER COLUMN ends_at SET NOT NULL;
