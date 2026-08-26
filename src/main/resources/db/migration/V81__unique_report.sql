-- L'entité Report déclare une contrainte unique_report (reporter_id,
-- reported_entity_type, reported_entity_id) depuis sa création, mais
-- ddl-auto=none et aucune migration ne l'a jamais posée : elle n'a donc jamais
-- existé en base. Le refus « vous avez déjà signalé cet élément » ne tenait
-- qu'au SELECT préalable du service, que deux requêtes concurrentes franchissent
-- toutes les deux.
--
-- ReportService rattrape désormais la violation et la rend en 409
-- REPORT_ALREADY_SUBMITTED, comme le refus qu'il servait déjà — la contrainte
-- n'ouvre donc pas de nouveau chemin d'erreur côté client.

-- Les doublons éventuellement déjà écrits, avant que la contrainte n'existe :
-- on garde le premier signalement de chaque triplet, celui qui porte la
-- description d'origine. L'id départage les créations de même horodatage.
DELETE FROM reports r
USING reports garde
WHERE r.reporter_id = garde.reporter_id
  AND r.reported_entity_type = garde.reported_entity_type
  AND r.reported_entity_id = garde.reported_entity_id
  AND (r.created_at, r.id) > (garde.created_at, garde.id);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'unique_report') THEN
    ALTER TABLE reports ADD CONSTRAINT unique_report
        UNIQUE (reporter_id, reported_entity_type, reported_entity_id);
  END IF;
END $$;
