-- V44: Données de test pour les tables meetDo (slot_participations, attendances,
-- activity_alerts), entièrement rattachées au jeu de données existant (V27 —
-- 10 utilisateurs/activités/programmes/créneaux "Allemagne"). Objectif :
-- fournir un jeu de données cohérent de bout en bout pour le frontend/QA,
-- sans dupliquer ni contredire les données déjà seedées.
--
-- Portée : cible volontairement le coeur de données V27 (ids 00000000..10,
-- 40000000..10, 50000000..10), qui reste le jeu de référence utilisé pour le
-- développement (utilisateur préservé seyd.njoya@icloud.com = user 1).
--
-- Cohérence garantie avec la logique applicative :
--   - Aucun hôte ne rejoint son propre créneau.
--   - Aucun doublon (schedule_id, user_id) dans slot_participations/attendances.
--   - Chaque Attendance correspond à un utilisateur réellement éligible
--     (hôte, ou participant CONFIRMED du même schedule via slot_participations
--     ou user_programs) — comme l'exige AttendanceService.confirm().
--   - Une présence n'est confirmable qu'après la fin du créneau : les deux
--     nouveaux schedules créés pour les attendances sont bien dans le passé.
--   - schedules.participant_count est recalculé à la fin sur TOUTES les
--     lignes, en sommant user_programs (ACTIVE) + slot_participations
--     (CONFIRMED) — exactement la requête combinée utilisée par
--     ScheduleRepository.countConfirmedParticipants côté application.
--   - users.attendance_count / distinct_partners_count sont recalculés à
--     partir des attendances insérées (mêmes règles que
--     PracticeStatsService.recalculateFor : on ne compte que wasPresent=TRUE,
--     les partenaires distincts sont les autres présents sur le même
--     schedule). current_streak_weeks est fixé à 1 pour les utilisateurs
--     ayant une présence récente (< 2 semaines) : une valeur exacte dépend de
--     la date d'exécution de la migration, mais ce compteur est de toute
--     façon recalculé par l'application dès la prochaine confirmation réelle.

-- ============================================================
-- 0. Deux créneaux passés (nécessaires pour tester la boucle de présence :
--    AttendanceService.confirm() exige que le créneau soit déjà terminé).
--    Rattachés à des programmes existants, ne modifient aucune donnée V27.
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — inserting past schedules for attendance testing...'; END $$;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public,
    show_exact_address, starts_at, ends_at, recurrence_rule, max_participants,
    is_open_to_partners, status, welcome_note, created_at)
VALUES
  -- Session de course à pied déjà passée (programme "Berlin Marathon Vorbereitung", hôte user 1)
  ('50000000-0000-0000-0000-000000000101',
   '40000000-0000-0000-0000-000000000001',
   'Volkspark Friedrichshain', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(13.4336, 52.5273), 4326),
   'Am Volkspark 1, 10243 Berlin', TRUE,
   NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days' + INTERVAL '75 minutes',
   NULL, 8, TRUE, 'PAST', 'Tempolauf — tous niveaux bienvenus.',
   NOW() - INTERVAL '12 days'),

  -- Session de bloc déjà passée (programme "Bouldern für Fortgeschrittene", hôte user 4)
  ('50000000-0000-0000-0000-000000000102',
   '40000000-0000-0000-0000-000000000004',
   'Kletterzentrum Köln', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(6.9441, 50.9226), 4326),
   'Schanzenstraße 6-20, 51063 Köln', TRUE,
   NOW() - INTERVAL '7 days', NOW() - INTERVAL '7 days' + INTERVAL '120 minutes',
   NULL, 8, TRUE, 'PAST', 'Session découverte — matériel fourni.',
   NOW() - INTERVAL '9 days');

-- ============================================================
-- 1. SLOT_PARTICIPATIONS
--    RSVP léger sur des créneaux ouverts existants (les 10 schedules V27,
--    tous is_open_to_partners=TRUE par défaut) + sur les 2 créneaux passés
--    ci-dessus (nécessaires pour que les attendances de la section 2 soient
--    autorisées). On évite systématiquement l'hôte du programme et
--    l'utilisateur déjà inscrit via user_programs sur le même schedule.
--    Statuts utilisés : uniquement CONFIRMED et WITHDRAWN, les deux seuls
--    statuts réellement produits par SlotService (join → CONFIRMED,
--    leave → WITHDRAWN) — INTERESTED/DECLINED existent dans l'enum pour un
--    futur flux d'approbation hôte non encore implémenté.
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — inserting slot_participations...'; END $$;

INSERT INTO slot_participations (id, schedule_id, user_id, status, join_message, created_at)
VALUES
  -- Créneau 1 (Berlin Marathon, hôte user1, déjà rejoint par user2 via user_programs)
  ('D4000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003',
   'CONFIRMED', 'Ich laufe im gleichen Tempo, darf ich dazukommen?', NOW() - INTERVAL '4 days'),
  ('D4000000-0000-0000-0000-000000000002',
   '50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000009',
   'CONFIRMED', NULL, NOW() - INTERVAL '2 days'),

  -- Créneau 2 (Hatha Yoga München, hôte user2, déjà rejoint par user3)
  ('D4000000-0000-0000-0000-000000000003',
   '50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000010',
   'CONFIRMED', 'Ich unterrichte selbst Pilates, würde gerne reinschnuppern.', NOW() - INTERVAL '3 days'),

  -- Créneau 3 (Halbmarathon Hamburg, hôte user3, déjà rejoint par user4)
  ('D4000000-0000-0000-0000-000000000004',
   '50000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
   'CONFIRMED', NULL, NOW() - INTERVAL '1 day'),

  -- Créneau 4 (Bouldern Köln, hôte user4, déjà rejoint par user5) — exemple de désistement
  ('D4000000-0000-0000-0000-000000000005',
   '50000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000009',
   'WITHDRAWN', 'Klingt super!', NOW() - INTERVAL '6 days'),

  -- Créneau 6 (Triathlon Schwimmtraining Stuttgart, hôte user6, déjà rejoint par user7)
  ('D4000000-0000-0000-0000-000000000006',
   '50000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000003',
   'CONFIRMED', 'Neugierig auf Triathlon, darf ich mal mitschwimmen?', NOW() - INTERVAL '2 days'),

  -- Créneau 9 (Gravel-Tour, hôte user9, déjà rejoint par user10)
  ('D4000000-0000-0000-0000-000000000007',
   '50000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000005',
   'CONFIRMED', NULL, NOW() - INTERVAL '5 days'),

  -- Créneau 10 (Pilates & Barre Bremen, hôte user10, déjà rejoint par user1)
  ('D4000000-0000-0000-0000-000000000008',
   '50000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000002',
   'CONFIRMED', 'Als Yogalehrerin interessiert mich der Barre-Ansatz.', NOW() - INTERVAL '1 day'),

  -- Créneau passé 101 (course à pied) — participants éligibles à la confirmation de présence
  ('D4000000-0000-0000-0000-000000000009',
   '50000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000003',
   'CONFIRMED', NULL, NOW() - INTERVAL '11 days'),
  ('D4000000-0000-0000-0000-000000000010',
   '50000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000009',
   'CONFIRMED', NULL, NOW() - INTERVAL '11 days'),

  -- Créneau passé 102 (bloc) — participants éligibles à la confirmation de présence
  ('D4000000-0000-0000-0000-000000000011',
   '50000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001',
   'CONFIRMED', NULL, NOW() - INTERVAL '8 days'),
  ('D4000000-0000-0000-0000-000000000012',
   '50000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000005',
   'CONFIRMED', NULL, NOW() - INTERVAL '8 days');

-- ============================================================
-- 2. ATTENDANCES
--    Confirmations de présence sur les deux créneaux passés. Chaque ligne
--    correspond à un utilisateur réellement éligible (hôte du programme ou
--    participant CONFIRMED inséré ci-dessus). user9 illustre le cas
--    was_present=FALSE ("finalement je n'y suis pas allé").
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — inserting attendances...'; END $$;

INSERT INTO attendances (id, schedule_id, user_id, was_present, attended_at, confirmed_at)
VALUES
  -- Créneau passé 101 (course à pied) : user1 (hôte) et user3 présents, user9 absent
  ('E4000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001',
   TRUE, NOW() - INTERVAL '10 days', NOW() - INTERVAL '9 days'),
  ('E4000000-0000-0000-0000-000000000002',
   '50000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000003',
   TRUE, NOW() - INTERVAL '10 days', NOW() - INTERVAL '9 days'),
  ('E4000000-0000-0000-0000-000000000003',
   '50000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000009',
   FALSE, NOW() - INTERVAL '10 days', NOW() - INTERVAL '9 days'),

  -- Créneau passé 102 (bloc) : user4 (hôte), user1 et user5 tous présents
  ('E4000000-0000-0000-0000-000000000004',
   '50000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000004',
   TRUE, NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days'),
  ('E4000000-0000-0000-0000-000000000005',
   '50000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001',
   TRUE, NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days'),
  ('E4000000-0000-0000-0000-000000000006',
   '50000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000005',
   TRUE, NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days');

-- ============================================================
-- 3. ACTIVITY_ALERTS
--    Alertes sur des activités que l'utilisateur ne pratique PAS déjà
--    lui-même (voir user_activities), positionnées sur sa propre ville.
--    Couvre les trois cas : jamais déclenchée, déclenchée récemment
--    (< 7 jours, doit rester en cooldown), déclenchée il y a longtemps
--    (> 7 jours, doit pouvoir se redéclencher), et alerte désactivée
--    (ne doit jamais déclencher de notification).
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — inserting activity_alerts...'; END $$;

INSERT INTO activity_alerts (id, user_id, activity_id, location, radius_meters, is_active, last_triggered_at, created_at)
VALUES
  -- user2 (Lena, München, Yoga) attend le bouldern près de chez elle — jamais déclenchée
  ('F4000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000003',
   ST_SetSRID(ST_MakePoint(11.5820, 48.1351), 4326), 15000, TRUE, NULL, NOW() - INTERVAL '20 days'),

  -- user6 (Sophie, Stuttgart, Schwimmen) attend le krafttraining — jamais déclenchée
  ('F4000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000004',
   ST_SetSRID(ST_MakePoint(9.1770, 48.7758), 4326), 10000, TRUE, NULL, NOW() - INTERVAL '15 days'),

  -- user7 (Tobias, Düsseldorf, Kickboxen) attend le laufen — déclenchée il y a 3 jours (cooldown actif)
  ('F4000000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000001',
   ST_SetSRID(ST_MakePoint(6.7735, 51.2217), 4326), 20000, TRUE, NOW() - INTERVAL '3 days', NOW() - INTERVAL '25 days'),

  -- user8 (Julia, Leipzig, Salsa) attend le freistilschwimmen — déclenchée il y a 10 jours (cooldown expiré)
  ('F4000000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000006',
   ST_SetSRID(ST_MakePoint(12.3731, 51.3397), 4326), 8000, TRUE, NOW() - INTERVAL '10 days', NOW() - INTERVAL '30 days'),

  -- user10 (Sarah, Bremen, Yoga/Pilates) attend le mountainbike — DÉSACTIVÉE, ne doit jamais matcher
  ('F4000000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000005',
   ST_SetSRID(ST_MakePoint(8.8017, 53.0793), 4326), 25000, FALSE, NOW() - INTERVAL '40 days', NOW() - INTERVAL '45 days'),

  -- user1 (Seyd, Berlin, Laufen) attend le kickboxen — jamais déclenchée
  ('F4000000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000007',
   ST_SetSRID(ST_MakePoint(13.4050, 52.5200), 4326), 12000, TRUE, NULL, NOW() - INTERVAL '5 days');

-- ============================================================
-- 4. Recalcul de schedules.participant_count sur TOUTES les lignes, avec
--    exactement la même règle combinée que
--    ScheduleRepository.countConfirmedParticipants : somme des inscriptions
--    actives via user_programs et des RSVP confirmés via slot_participations.
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — recomputing schedules.participant_count...'; END $$;

UPDATE schedules s
SET participant_count =
    (SELECT COUNT(*) FROM user_programs up WHERE up.schedule_id = s.id AND up.status = 'ACTIVE')
  + (SELECT COUNT(*) FROM slot_participations sp WHERE sp.schedule_id = s.id AND sp.status = 'CONFIRMED');

-- ============================================================
-- 5. Recalcul des compteurs dénormalisés sur users, à partir des attendances
--    insérées ci-dessus — mêmes règles que PracticeStatsService :
--    attendance_count = présences confirmées ; distinct_partners_count =
--    autres personnes présentes sur les mêmes créneaux ; last_attendance_at =
--    date de la dernière présence. current_streak_weeks est fixé à 1 pour
--    toute personne ayant une présence dans les 14 derniers jours (toutes
--    les présences seedées ci-dessus le sont) — se recalculera correctement
--    dès la prochaine confirmation réelle via l'application.
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V44 — recomputing users practice stats...'; END $$;

UPDATE users u
SET attendance_count = COALESCE((
      SELECT COUNT(*) FROM attendances a
      WHERE a.user_id = u.id AND a.was_present = TRUE
    ), 0),
    distinct_partners_count = COALESCE((
      SELECT COUNT(DISTINCT other.user_id)
      FROM attendances mine
      JOIN attendances other ON other.schedule_id = mine.schedule_id
                            AND other.user_id <> mine.user_id
      WHERE mine.user_id = u.id
        AND mine.was_present = TRUE
        AND other.was_present = TRUE
    ), 0),
    last_attendance_at = (
      SELECT MAX(a.attended_at) FROM attendances a
      WHERE a.user_id = u.id AND a.was_present = TRUE
    ),
    current_streak_weeks = CASE WHEN EXISTS (
      SELECT 1 FROM attendances a
      WHERE a.user_id = u.id AND a.was_present = TRUE
        AND a.attended_at > NOW() - INTERVAL '14 days'
    ) THEN 1 ELSE 0 END
WHERE u.id IN (
    SELECT DISTINCT user_id FROM attendances
);
