-- La table des signalements et l'entité Report ne parlaient pas le même
-- vocabulaire, et personne ne s'en apercevait tant qu'on n'y lisait rien.
--
-- V9 a créé la table avec « status VARCHAR(20) NOT NULL DEFAULT 'OPEN' » et son
-- propre jeu de mots — OPEN, RESOLVED. L'entité, écrite plus tard, déclare
-- ReportStatus = PENDING, REVIEWED, ACTIONED, DISMISSED, et le champ est
-- @Enumerated(EnumType.STRING). Les deux vocabulaires ne se recouvrent que sur
-- DISMISSED. Les seeds (V12, V13, V27) ont suivi celui de la table.
--
-- Conséquence : à chaque lecture, Hibernate appelle ReportStatus.valueOf("OPEN")
-- et lève IllegalArgumentException — GET /api/reports/me rendait 500 pour tout
-- compte possédant une ligne semée. L'écriture, elle, ne montrait rien :
-- createReport pose PENDING en dur, il n'y a jamais de conversion entrante.
-- C'est ce qui a fait passer le défaut pour un problème de sérialisation.
--
-- Le plus coûteux n'était pas ce 500. Les six signalements semés « ouverts »
-- n'étaient pas PENDING, donc GET /api/reports/pending rendait une file de
-- modération vide alors qu'elle ne l'était pas.

-- OPEN -> PENDING : même sens, « personne ne l'a encore regardé ».
UPDATE reports SET status = 'PENDING' WHERE status = 'OPEN';

-- RESOLVED -> REVIEWED, et non ACTIONED : « clos » ne dit pas si une sanction a
-- suivi. Parmi les lignes semées, certaines notes décrivent un avertissement,
-- une autre conclut à l'absence de manquement — REVIEWED est le seul des deux
-- qui ne prête aucune décision à des lignes qui n'en portent pas.
UPDATE reports SET status = 'REVIEWED' WHERE status = 'RESOLVED';

-- MISLEADING_INFORMATION n'a jamais existé dans ReportReason. Le motif retombe
-- sur OTHER ; le détail n'est pas perdu, il est dans description. Ajouter la
-- valeur à l'enum serait l'autre issue, mais elle change le contrat que l'app
-- lit — à arbitrer côté produit, pas dans un correctif de données.
UPDATE reports SET reason = 'OTHER' WHERE reason = 'MISLEADING_INFORMATION';

-- Le défaut de colonne écrivait le mot qui casse la lecture. Une ligne insérée
-- sans statut — par un script, une reprise, un seed futur — recréait le défaut
-- à elle seule.
ALTER TABLE reports ALTER COLUMN status SET DEFAULT 'PENDING';

-- Si une valeur imprévue restait malgré les trois UPDATE ci-dessus, le CHECK la
-- refuserait et Flyway ferait échouer le démarrage sur une violation de
-- contrainte, sans dire laquelle. On préfère échouer aussi — une ligne de
-- modération ne se réécrit pas au hasard — mais en nommant le coupable.
DO $$
DECLARE inconnus TEXT;
BEGIN
  SELECT string_agg(DISTINCT status, ', ') INTO inconnus
  FROM reports WHERE status NOT IN ('PENDING', 'REVIEWED', 'ACTIONED', 'DISMISSED');
  IF inconnus IS NOT NULL THEN
    RAISE EXCEPTION 'reports.status hors du vocabulaire de ReportStatus : %. '
      'Ajouter la correspondance dans cette migration avant de rejouer.', inconnus;
  END IF;

  SELECT string_agg(DISTINCT reason, ', ') INTO inconnus
  FROM reports WHERE reason NOT IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE_CONTENT',
                                    'FAKE_PROFILE', 'VIOLENCE', 'HATE_SPEECH', 'OTHER');
  IF inconnus IS NOT NULL THEN
    RAISE EXCEPTION 'reports.reason hors du vocabulaire de ReportReason : %. '
      'Ajouter la correspondance dans cette migration avant de rejouer.', inconnus;
  END IF;
END $$;

-- Sans ces deux contraintes, la normalisation ci-dessus ne vaudrait que pour les
-- lignes d'aujourd'hui. C'est ce qui empêche un vocabulaire parallèle de
-- réapparaître : la base refuse désormais un mot que l'enum ne connaît pas.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reports_status_vocabulaire') THEN
    ALTER TABLE reports ADD CONSTRAINT reports_status_vocabulaire
        CHECK (status IN ('PENDING', 'REVIEWED', 'ACTIONED', 'DISMISSED'));
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reports_reason_vocabulaire') THEN
    ALTER TABLE reports ADD CONSTRAINT reports_reason_vocabulaire
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE_CONTENT',
                          'FAKE_PROFILE', 'VIOLENCE', 'HATE_SPEECH', 'OTHER'));
  END IF;
END $$;

