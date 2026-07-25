-- GET /api/slots/feed (fenêtre "maintenant -> +7 jours") était systématiquement
-- vide en démo pour deux raisons combinées :
--
-- 1. Les schedules de seed (V27/V28) fixent starts_at à `NOW() + INTERVAL`
--    calculé une seule fois, au moment où la migration s'exécute. Passé ce
--    délai (quelques jours), toutes ces dates deviennent définitivement
--    passées — une migration ne se rejoue jamais. On rattrape ici les
--    schedules récurrents (recurrence_rule non nul) déjà passés en les
--    avançant du nombre de semaines nécessaire pour retomber dans le futur,
--    et le job RecurringSlotRolloverJob fait perpétuellement ce travail
--    ensuite pour que le problème ne revienne jamais.
--
-- 2. Tous les schedules proches de Munich (V27 + V28) appartiennent au même
--    hôte (Lena Müller, via user_activity 30000000-...-0002) : un compte de
--    démo positionné à Munich et propriétaire de ces créneaux ne voit rien,
--    puisque /api/slots/feed exclut volontairement les créneaux de
--    l'appelant. On ajoute un second hôte avec un créneau ouvert à Munich.

-- ---- 1. Rattrapage des schedules récurrents déjà passés ----
UPDATE schedules
SET starts_at = starts_at + (CEIL(EXTRACT(EPOCH FROM (NOW() - starts_at)) / 604800.0) * INTERVAL '7 days'),
    ends_at   = CASE WHEN ends_at IS NOT NULL
                     THEN ends_at + (CEIL(EXTRACT(EPOCH FROM (NOW() - starts_at)) / 604800.0) * INTERVAL '7 days')
                     ELSE NULL END,
    status    = 'OPEN',
    participant_count = 0
WHERE recurrence_rule IS NOT NULL
  AND starts_at < NOW();

-- ---- 2. Second hôte avec un créneau ouvert près de Munich ----
-- Réutilise l'utilisateur existant Sarah Richter (Bremen, Pilates/Yoga —
-- thématiquement cohérente avec les créneaux de yoga déjà présents à Munich),
-- via son user_activity existante 30000000-...-0010.
INSERT INTO programs (id, user_activity_id, title, description, status, is_public,
    organizer_name, organizer_avatar_url, next_session_at,
    duration_weeks, sessions_per_week, session_duration_minutes,
    preferred_days, preferred_time, max_participants, privacy,
    goals, prerequisites, location_type,
    created_at, updated_at)
VALUES
  ('40000000-0000-0000-0000-000000000041',
   '30000000-0000-0000-0000-000000000010',
   'Pilates Pop-up München',
   'Session ponctuelle de Pilates lors d''un passage à Munich. Ouvert à toutes celles et ceux qui veulent découvrir ou pratiquer entre voyageurs.',
   'ACTIVE', TRUE, 'Sarah Richter',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=sarah',
   NOW() + INTERVAL '3 days',
   1, 1, 60, ARRAY[3], 'MORNING', 10, 'PUBLIC',
   'Se retrouver pour une séance conviviale.',
   'Aucun.',
   'IN_PERSON', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public,
    show_exact_address, starts_at, ends_at, recurrence_rule, max_participants,
    is_open_to_partners, status, welcome_note, created_at)
VALUES
  ('50000000-0000-0000-0000-000000000041',
   '40000000-0000-0000-0000-000000000041',
   'Olympiapark München', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(11.5497, 48.1755), 4326),
   'Spiridon-Louis-Ring 21, 80809 München', TRUE,
   NOW() + INTERVAL '3 days', NOW() + INTERVAL '3 days' + INTERVAL '60 minutes',
   NULL, 10, TRUE, 'OPEN', 'Débutants bienvenus, tapis fourni.',
   NOW() - INTERVAL '2 days')
ON CONFLICT (id) DO NOTHING;
