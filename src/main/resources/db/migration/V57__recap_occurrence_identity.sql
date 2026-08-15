-- meetDo : donner une identité à chaque OCCURRENCE d'un créneau récurrent.
--
-- Le problème corrigé ici est antérieur aux cartes-souvenirs, mais c'est
-- l'écran des cartes qui l'a rendu visible. Un créneau récurrent n'a qu'une
-- seule ligne dans `schedules`, et RecurringSlotRolloverJob la réécrit en
-- place : `starts_at` est avancé à l'occurrence suivante. Une occurrence
-- passée ne laisse donc aucune trace, et tout ce qui la décrit se retrouve
-- rattaché à une ligne qui, dix minutes plus tard, parle d'un moment futur.
--
-- Trois conséquences mesurées avant ce correctif :
--
--   1. `slot_recaps.schedule_id` étant UNIQUE, un créneau hebdomadaire ne
--      pouvait porter qu'UNE carte-souvenir, réutilisée d'une semaine sur
--      l'autre ;
--   2. la date affichée sur cette carte (`schedules.starts_at`) était celle de
--      la PROCHAINE séance, donc dans le futur, pour un moment déjà passé ;
--   3. `attendances` étant UNIQUE (schedule_id, user_id), une même personne ne
--      pouvait jamais confirmer sa présence à deux séances du même créneau
--      récurrent.
--
-- La correction n'introduit pas de ligne par occurrence — cela romprait toutes
-- les inscriptions, conversations et notifications qui pointent sur
-- `schedule_id`. Elle nomme l'occurrence par l'instant où elle a commencé, et
-- fait de ce couple la clé des artefacts qui décrivent un moment passé.

-- ————————————————————————————————————————————————————————————————
-- 1. La dernière occurrence retirée par le rollover
-- ————————————————————————————————————————————————————————————————
--
-- Écrite par RecurringSlotRolloverJob au moment où il avance `starts_at`,
-- c'est-à-dire au seul instant où le système sait encore quel moment vient de
-- se terminer. Sans elle, une séance passée devient irrécupérable dès le
-- passage suivant du job — la RRULE ne suffit pas, puisque le rollover a
-- écrasé l'ancre dont elle se déduit.
--
-- Une seule occurrence est conservée, pas un historique : au-delà de sept
-- jours plus rien n'est contribuable, et les moments qui ont laissé une trace
-- ont déjà leur ligne dans `slot_recaps`.
ALTER TABLE schedules
    ADD COLUMN last_occurrence_start TIMESTAMPTZ,
    ADD COLUMN last_occurrence_end   TIMESTAMPTZ;

COMMENT ON COLUMN schedules.last_occurrence_start IS
    'Début de la dernière occurrence retirée par RecurringSlotRolloverJob. '
    'Nulle pour un créneau non récurrent, ou jamais encore avancé.';

-- ————————————————————————————————————————————————————————————————
-- 2. Une présence porte sur une occurrence, pas sur une ligne
-- ————————————————————————————————————————————————————————————————
--
-- `attended_at` porte déjà exactement cette information — « instant réel du
-- créneau », dénormalisé pour le calcul de série. Aucune colonne nouvelle
-- n'est donc nécessaire : il suffit de l'admettre dans la clé d'unicité.
ALTER TABLE attendances DROP CONSTRAINT IF EXISTS uq_attendance;
ALTER TABLE attendances
    ADD CONSTRAINT uq_attendance UNIQUE (schedule_id, user_id, attended_at);

CREATE INDEX IF NOT EXISTS idx_attendance_schedule_occurrence
    ON attendances(schedule_id, attended_at);

-- ————————————————————————————————————————————————————————————————
-- 3. Une carte-souvenir porte sur une occurrence
-- ————————————————————————————————————————————————————————————————
--
-- `occurrence_end` est stocké plutôt que recalculé depuis le créneau : la
-- fenêtre de contribution de sept jours en découle, et une carte doit se figer
-- pour de bon. Recalculer la fin depuis `schedules` ferait rouvrir une fenêtre
-- close le jour où l'hôte allonge la durée de son créneau — sous les yeux de
-- quelqu'un qui a déjà partagé la carte.
ALTER TABLE slot_recaps
    ADD COLUMN occurrence_start TIMESTAMPTZ,
    ADD COLUMN occurrence_end   TIMESTAMPTZ;

-- Reprise des cartes existantes. La meilleure source disponible est la
-- présence de leurs contributeurs : `attended_at` a été posé au moment de la
-- confirmation, donc avant tout rollover ultérieur. `schedules.starts_at` ne
-- sert que de repli pour une carte sans aucune présence — ce qui n'existe pas
-- en pratique, une carte naissant d'une contribution qui exige une présence.
UPDATE slot_recaps r
SET occurrence_start = COALESCE(
        (SELECT MIN(a.attended_at) FROM attendances a WHERE a.schedule_id = r.schedule_id),
        s.starts_at)
FROM schedules s
WHERE s.id = r.schedule_id;

UPDATE slot_recaps r
SET occurrence_end = r.occurrence_start
        + COALESCE(s.ends_at - s.starts_at, INTERVAL '2 hours')
FROM schedules s
WHERE s.id = r.schedule_id;

ALTER TABLE slot_recaps
    ALTER COLUMN occurrence_start SET NOT NULL,
    ALTER COLUMN occurrence_end   SET NOT NULL;

-- L'unicité passe de « une carte par créneau » à « une carte par occurrence ».
-- La contrainte d'origine était déclarée en ligne (`schedule_id UUID NOT NULL
-- UNIQUE`), donc nommée par PostgreSQL : on la retrouve par sa définition
-- plutôt que de parier sur `slot_recaps_schedule_id_key`.
DO $$
DECLARE constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'slot_recaps'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (schedule_id)';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE slot_recaps DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE slot_recaps
    ADD CONSTRAINT uq_recap_occurrence UNIQUE (schedule_id, occurrence_start);

-- Les trois nouvelles lectures (programme, activité, profil) trient toutes par
-- occurrence décroissante.
CREATE INDEX idx_recaps_occurrence ON slot_recaps(occurrence_start DESC);
