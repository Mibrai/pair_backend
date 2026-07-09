-- V27: Reset database and seed with German data (10 rows per table)
-- Keeps only the user seyd.njoya@icloud.com, then inserts fresh data.

-- ============================================================
-- 0. TRUNCATE ALL DATA (cascade respects FK order)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — truncating all tables...'; END $$;

TRUNCATE TABLE
    audit_logs,
    progression_entries,
    progressions,
    search_logs,
    device_tokens,
    notification_prefs,
    notifications,
    badge_awards,
    badges,
    peer_recommendations,
    review_criteria,
    reviews,
    program_activities,
    program_progress,
    user_programs,
    program_enrollments,
    program_media,
    schedules,
    programs,
    user_activities,
    activities,
    categories,
    message_edit_history,
    messages,
    conversation_members,
    conversations,
    reports,
    users
CASCADE;

-- ============================================================
-- 1. USERS (10 rows, all Germany)
-- Coordinates are real German cities (lng, lat).
-- Password hash = bcrypt("Pair2024!")
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting users...'; END $$;

INSERT INTO users (id, email, password_hash, phone, display_name, bio, avatar_url,
    location, blur_radius_m, location_public, online_status_visible, receive_messages,
    verification_status, verified_at, created_at, last_active_at, is_active,
    profile_visibility, show_age, show_last_active, show_location, allow_messages, show_on_map)
VALUES
  -- User 0: the preserved account (seyd.njoya@icloud.com)
  ('00000000-0000-0000-0000-000000000001',
   'seyd.njoya@icloud.com',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491701234560', 'Seyd Njoya',
   'Sportbegeistert aus Berlin. Ich liebe Laufen, Yoga und Klettern.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=seyd',
   ST_SetSRID(ST_MakePoint(13.4050, 52.5200), 4326),
   300, TRUE, TRUE, TRUE,
   'VERIFIED', NOW() - INTERVAL '60 days',
   NOW() - INTERVAL '90 days', NOW() - INTERVAL '2 hours', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000002',
   'lena.mueller@web.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491711234561', 'Lena Müller',
   'Yogalehrerin aus München. Ich unterrichte Hatha und Vinyasa seit 8 Jahren.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=lena',
   ST_SetSRID(ST_MakePoint(11.5820, 48.1351), 4326),
   400, TRUE, TRUE, TRUE,
   'VERIFIED', NOW() - INTERVAL '55 days',
   NOW() - INTERVAL '80 days', NOW() - INTERVAL '1 hour', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000003',
   'max.schmidt@gmx.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491721234562', 'Max Schmidt',
   'Hobbyläufer und Radfahrer aus Hamburg. Halbmarathon unter 2 Stunden ist mein Ziel.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=max',
   ST_SetSRID(ST_MakePoint(9.9937, 53.5511), 4326),
   500, TRUE, FALSE, TRUE,
   'VERIFIED', NOW() - INTERVAL '40 days',
   NOW() - INTERVAL '70 days', NOW() - INTERVAL '3 hours', TRUE,
   'PUBLIC', TRUE, TRUE, FALSE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000004',
   'anna.weber@t-online.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491731234563', 'Anna Weber',
   'Kletterbegeisterte aus Köln. Bouldern und Vorstieg im Kletterzentrum und draußen.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=anna',
   ST_SetSRID(ST_MakePoint(6.9603, 50.9333), 4326),
   350, TRUE, TRUE, TRUE,
   'UNVERIFIED', NULL,
   NOW() - INTERVAL '60 days', NOW() - INTERVAL '30 minutes', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000005',
   'felix.bauer@freenet.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491741234564', 'Felix Bauer',
   'Personal Trainer aus Frankfurt. Krafttraining, HIIT und funktionelles Training.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=felix',
   ST_SetSRID(ST_MakePoint(8.6821, 50.1109), 4326),
   500, TRUE, TRUE, TRUE,
   'VERIFIED', NOW() - INTERVAL '30 days',
   NOW() - INTERVAL '50 days', NOW() - INTERVAL '5 minutes', TRUE,
   'PUBLIC', FALSE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000006',
   'sophie.hoffmann@yahoo.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491751234565', 'Sophie Hoffmann',
   'Schwimmerin und Triathletin aus Stuttgart. Ironman-Teilnehmerin seit 2021.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=sophie',
   ST_SetSRID(ST_MakePoint(9.1770, 48.7758), 4326),
   600, FALSE, FALSE, TRUE,
   'VERIFIED', NOW() - INTERVAL '25 days',
   NOW() - INTERVAL '45 days', NOW() - INTERVAL '2 days', TRUE,
   'FRIENDS', TRUE, FALSE, FALSE, 'FRIENDS', FALSE),

  ('00000000-0000-0000-0000-000000000007',
   'tobias.wagner@posteo.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491761234566', 'Tobias Wagner',
   'Kampfsportler aus Düsseldorf. Kickboxen und Brazilian Jiu-Jitsu seit 10 Jahren.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=tobias',
   ST_SetSRID(ST_MakePoint(6.7735, 51.2217), 4326),
   400, TRUE, TRUE, TRUE,
   'VERIFIED', NOW() - INTERVAL '20 days',
   NOW() - INTERVAL '35 days', NOW() - INTERVAL '1 day', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000008',
   'julia.braun@protonmail.com',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491771234567', 'Julia Braun',
   'Tanzlehrerin aus Leipzig. Salsa, Tango und modernen Tanz unterrichte ich mit Leidenschaft.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=julia',
   ST_SetSRID(ST_MakePoint(12.3731, 51.3397), 4326),
   300, TRUE, TRUE, TRUE,
   'UNVERIFIED', NULL,
   NOW() - INTERVAL '40 days', NOW() - INTERVAL '6 hours', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000009',
   'markus.fischer@icloud.com',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491781234568', 'Markus Fischer',
   'Radsportler aus Nürnberg. Gravel und Mountainbike in der fränkischen Schweiz.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=markus',
   ST_SetSRID(ST_MakePoint(11.0767, 49.4521), 4326),
   500, TRUE, FALSE, TRUE,
   'VERIFIED', NOW() - INTERVAL '15 days',
   NOW() - INTERVAL '30 days', NOW() - INTERVAL '4 hours', TRUE,
   'PUBLIC', TRUE, TRUE, FALSE, 'EVERYONE', TRUE),

  ('00000000-0000-0000-0000-000000000010',
   'sarah.richter@gmx.de',
   '$2a$12$KIX8e2zR7k4aN3bQ1uO5XeABCDEF123456789012345678901234',
   '+491791234569', 'Sarah Richter',
   'Pilates- und Barre-Trainerin aus Bremen. Ich helfe dir, Kraft und Flexibilität aufzubauen.',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=sarah',
   ST_SetSRID(ST_MakePoint(8.8017, 53.0793), 4326),
   400, TRUE, TRUE, TRUE,
   'VERIFIED', NOW() - INTERVAL '10 days',
   NOW() - INTERVAL '25 days', NOW() - INTERVAL '30 minutes', TRUE,
   'PUBLIC', TRUE, TRUE, TRUE, 'EVERYONE', TRUE);

-- ============================================================
-- 2. CATEGORIES (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting categories...'; END $$;

INSERT INTO categories (id, name, icon, color_ramp) VALUES
  ('10000000-0000-0000-0000-000000000001', 'Laufsport',       'directions_run',    'orange-red'),
  ('10000000-0000-0000-0000-000000000002', 'Yoga & Wellness', 'self_improvement',  'purple-violet'),
  ('10000000-0000-0000-0000-000000000003', 'Klettern',        'terrain',           'brown-amber'),
  ('10000000-0000-0000-0000-000000000004', 'Krafttraining',   'fitness_center',    'blue-indigo'),
  ('10000000-0000-0000-0000-000000000005', 'Radsport',        'directions_bike',   'green-teal'),
  ('10000000-0000-0000-0000-000000000006', 'Schwimmen',       'pool',              'cyan-blue'),
  ('10000000-0000-0000-0000-000000000007', 'Kampfsport',      'sports_martial_arts','red-orange'),
  ('10000000-0000-0000-0000-000000000008', 'Tanzen',          'music_note',        'pink-rose'),
  ('10000000-0000-0000-0000-000000000009', 'Teamsport',       'groups',            'lime-green'),
  ('10000000-0000-0000-0000-000000000010', 'Wintersport',     'ac_unit',           'sky-blue');

-- ============================================================
-- 3. ACTIVITIES (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting activities...'; END $$;

INSERT INTO activities (id, category_id, name, slug, description, icon, created_at) VALUES
  ('20000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001',
   'Laufen', 'laufen',
   'Laufsport für alle Niveaus — von der ersten Runde im Park bis zum Marathon.',
   'directions_run', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000002',
   '10000000-0000-0000-0000-000000000002',
   'Hatha Yoga', 'hatha-yoga',
   'Klassisches Hatha Yoga mit Fokus auf Atemübungen und Körperhaltungen.',
   'self_improvement', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000003',
   '10000000-0000-0000-0000-000000000003',
   'Bouldern', 'bouldern',
   'Klettern ohne Seil an niedrigen Wänden — Technik und Kraft im Vordergrund.',
   'terrain', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000004',
   '10000000-0000-0000-0000-000000000004',
   'Krafttraining', 'krafttraining',
   'Funktionelles Krafttraining mit Freihanteln und eigenem Körpergewicht.',
   'fitness_center', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000005',
   '10000000-0000-0000-0000-000000000005',
   'Mountainbike', 'mountainbike',
   'Geländeradfahren auf Trails und Schotterpisten.',
   'directions_bike', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000006',
   '10000000-0000-0000-0000-000000000006',
   'Freistilschwimmen', 'freistilschwimmen',
   'Schwimmtraining im Freibad und Hallenbad, alle Leistungsstufen.',
   'pool', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000007',
   '10000000-0000-0000-0000-000000000007',
   'Kickboxen', 'kickboxen',
   'Schlag- und Tritttechnik, Kondition und Selbstverteidigung.',
   'sports_martial_arts', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000008',
   '10000000-0000-0000-0000-000000000008',
   'Salsa', 'salsa',
   'Salsa-Tanzkurse für Anfänger und Fortgeschrittene.',
   'music_note', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000009',
   '10000000-0000-0000-0000-000000000009',
   'Fußball', 'fussball',
   'Freizeitfußball im Verein und auf dem Bolzplatz.',
   'sports_soccer', NOW() - INTERVAL '100 days'),

  ('20000000-0000-0000-0000-000000000010',
   '10000000-0000-0000-0000-000000000010',
   'Skifahren', 'skifahren',
   'Alpin-Skifahren für Einsteiger und erfahrene Skifahrer.',
   'ac_unit', NOW() - INTERVAL '100 days');

-- ============================================================
-- 4. USER_ACTIVITIES (10 rows)
-- Each of the 10 users has one primary activity
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting user_activities...'; END $$;

INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at) VALUES
  ('30000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000001',
   TRUE, 'Ich laufe 4x pro Woche und suche Trainingspartner für den Berlin-Marathon.',
   'ADVANCED', 'DUO', NOW() - INTERVAL '85 days'),

  ('30000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000002',
   '20000000-0000-0000-0000-000000000002',
   TRUE, 'Zertifizierte Yogalehrerin — unterrichte Einzel- und Gruppenstunden in München.',
   'ADVANCED', 'GROUP', NOW() - INTERVAL '75 days'),

  ('30000000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000003',
   '20000000-0000-0000-0000-000000000001',
   TRUE, 'Laufe meistens am Alsterufer — suche Gleichgesinnte für Tempo-Läufe.',
   'INTERMEDIATE', 'DUO', NOW() - INTERVAL '65 days'),

  ('30000000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000004',
   '20000000-0000-0000-0000-000000000003',
   TRUE, 'Bouldere 3x pro Woche im Kletterzentrum Köln. Suche Partner für Outdoor-Touren.',
   'INTERMEDIATE', 'DUO', NOW() - INTERVAL '55 days'),

  ('30000000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000005',
   '20000000-0000-0000-0000-000000000004',
   TRUE, 'Biete Personal-Training-Sessions und Gruppentraining in Frankfurt an.',
   'ADVANCED', 'GROUP', NOW() - INTERVAL '45 days'),

  ('30000000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000006',
   '20000000-0000-0000-0000-000000000006',
   FALSE, 'Triathlon-Trainingsgruppe in Stuttgart — Schwerpunkt Schwimmen.',
   'ADVANCED', 'GROUP', NOW() - INTERVAL '40 days'),

  ('30000000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000007',
   '20000000-0000-0000-0000-000000000007',
   TRUE, 'Kickboxtrainer mit 10 Jahren Erfahrung. Anfänger herzlich willkommen.',
   'EXPERT', 'GROUP', NOW() - INTERVAL '30 days'),

  ('30000000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000008',
   '20000000-0000-0000-0000-000000000008',
   TRUE, 'Salsa-Tanzlehrerin in Leipzig — unterrichte Cuban und On2.',
   'EXPERT', 'DUO', NOW() - INTERVAL '35 days'),

  ('30000000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000009',
   '20000000-0000-0000-0000-000000000005',
   TRUE, 'Gravel- und MTB-Touren in der fränkischen Schweiz — alle Niveaus willkommen.',
   'ADVANCED', 'GROUP', NOW() - INTERVAL '25 days'),

  ('30000000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000010',
   '20000000-0000-0000-0000-000000000002',
   TRUE, 'Pilates und Barre in Bremen — kleine Gruppen, persönliche Betreuung.',
   'ADVANCED', 'GROUP', NOW() - INTERVAL '20 days');

-- ============================================================
-- 5. PROGRAMS (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting programs...'; END $$;

INSERT INTO programs (id, user_activity_id, title, description, status, is_public,
    organizer_name, organizer_avatar_url, next_session_at,
    duration_weeks, sessions_per_week, session_duration_minutes,
    preferred_days, preferred_time, max_participants, privacy,
    goals, prerequisites, location_type,
    created_at, updated_at)
VALUES
  ('40000000-0000-0000-0000-000000000001',
   '30000000-0000-0000-0000-000000000001',
   'Berlin Marathon Vorbereitung 2024',
   'Ein 12-Wochen-Programm zur Vorbereitung auf den Berlin-Marathon. Wir steigern die Umfänge schrittweise und arbeiten an Tempoläufen, langen Einheiten und Regeneration.',
   'ACTIVE', TRUE, 'Seyd Njoya',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=seyd',
   NOW() + INTERVAL '3 days',
   12, 4, 75, ARRAY[1,3,5,6], 'MORNING', 8, 'PUBLIC',
   'Marathon unter 4:30 Stunden finishen. Ausdauer aufbauen und Verletzungen vermeiden.',
   'Mindestens 3 Monate regelmäßiges Laufen. 10 km ohne Pause laufen können.',
   'IN_PERSON', NOW() - INTERVAL '60 days', NOW() - INTERVAL '2 days'),

  ('40000000-0000-0000-0000-000000000002',
   '30000000-0000-0000-0000-000000000002',
   'Hatha Yoga für Einsteiger — München',
   'Sanfte Einführung in Hatha Yoga. Wir lernen grundlegende Asanas, Pranayama und Entspannungstechniken. Ideal für Menschen ohne Vorkenntnisse.',
   'ACTIVE', TRUE, 'Lena Müller',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=lena',
   NOW() + INTERVAL '2 days',
   8, 2, 60, ARRAY[2,4], 'EVENING', 10, 'PUBLIC',
   'Grundlagen des Yoga erlernen. Stressabbau und mehr Körperbewusstsein entwickeln.',
   'Keine Vorkenntnisse nötig. Eigene Yogamatte mitbringen.',
   'IN_PERSON', NOW() - INTERVAL '55 days', NOW() - INTERVAL '1 day'),

  ('40000000-0000-0000-0000-000000000003',
   '30000000-0000-0000-0000-000000000003',
   'Halbmarathon Hamburg — 8 Wochen Plan',
   'Strukturierter Trainingsplan für den Hamburger Halbmarathon. Drei Einheiten pro Woche: Tempolauf, Fahrtspiel und langer Lauf am Wochenende.',
   'ACTIVE', TRUE, 'Max Schmidt',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=max',
   NOW() + INTERVAL '4 days',
   8, 3, 60, ARRAY[2,4,6], 'MORNING', 6, 'PUBLIC',
   'Halbmarathon unter 2:00 Stunden. Lauftechnik verbessern.',
   '10 km in unter 60 Minuten laufen können.',
   'IN_PERSON', NOW() - INTERVAL '50 days', NOW() - INTERVAL '3 days'),

  ('40000000-0000-0000-0000-000000000004',
   '30000000-0000-0000-0000-000000000004',
   'Bouldern für Fortgeschrittene — Köln',
   'Wöchentliche Bouldersessions im Kletterzentrum Köln für Mittelstufe und Fortgeschrittene. Wir arbeiten an Technik, Kraftübungen und gemeinsamen Projekten.',
   'ACTIVE', TRUE, 'Anna Weber',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=anna',
   NOW() + INTERVAL '5 days',
   10, 2, 120, ARRAY[3,6], 'AFTERNOON', 8, 'PUBLIC',
   'Boulder-Grad 6A+ sicher abschließen. Technik und Körperspannung verbessern.',
   'Mindestens 6 Monate Klettererfahrung. Grad 5B abschließen können.',
   'IN_PERSON', NOW() - INTERVAL '45 days', NOW() - INTERVAL '1 day'),

  ('40000000-0000-0000-0000-000000000005',
   '30000000-0000-0000-0000-000000000005',
   'Functional Fitness Bootcamp Frankfurt',
   'Intensives 6-Wochen-Bootcamp mit Fokus auf funktionelles Training, HIIT und Körpergewichtsübungen. Maximale Ergebnisse in kurzer Zeit.',
   'ACTIVE', TRUE, 'Felix Bauer',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=felix',
   NOW() + INTERVAL '1 day',
   6, 3, 45, ARRAY[1,3,5], 'MORNING', 12, 'PUBLIC',
   'Ganzkörperkraft steigern. Körperfett reduzieren und Grundkondition verbessern.',
   'Grundlegende Fitness empfohlen. Keine Verletzungen an Knie oder Schulter.',
   'IN_PERSON', NOW() - INTERVAL '40 days', NOW() - INTERVAL '12 hours'),

  ('40000000-0000-0000-0000-000000000006',
   '30000000-0000-0000-0000-000000000006',
   'Triathlon Schwimmtraining Stuttgart',
   'Schwimmspezifisches Training für Triathleten und ambitionierte Schwimmer. Beckenschwimmen, offenes Wasser und wettkampfspezifische Einheiten.',
   'ACTIVE', TRUE, 'Sophie Hoffmann',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=sophie',
   NOW() + INTERVAL '6 days',
   12, 3, 90, ARRAY[2,4,0], 'MORNING', 8, 'PUBLIC',
   '1,5 km Schwimmen in unter 35 Minuten. Wettkampfstart beim Stuttgarter Triathlon.',
   '200 m ohne Pause schwimmen können. Eigene Schwimmausrüstung.',
   'IN_PERSON', NOW() - INTERVAL '38 days', NOW() - INTERVAL '2 days'),

  ('40000000-0000-0000-0000-000000000007',
   '30000000-0000-0000-0000-000000000007',
   'Kickboxen Anfängerkurs — Düsseldorf',
   'Einsteigerkurs Kickboxen über 8 Wochen. Von der Grundstellung über Schlag- und Trittkombinationen bis zum ersten Sparring. Spaß und Sicherheit stehen im Vordergrund.',
   'ACTIVE', TRUE, 'Tobias Wagner',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=tobias',
   NOW() + INTERVAL '2 days',
   8, 2, 60, ARRAY[2,5], 'EVENING', 10, 'PUBLIC',
   'Grundlagen des Kickboxens beherrschen. Kondition und Selbstvertrauen aufbauen.',
   'Keine Vorkenntnisse nötig. Sportkleidung und Handschuhe erforderlich.',
   'IN_PERSON', NOW() - INTERVAL '30 days', NOW() - INTERVAL '1 day'),

  ('40000000-0000-0000-0000-000000000008',
   '30000000-0000-0000-0000-000000000008',
   'Salsa Cubana Anfängerkurs Leipzig',
   'Salsa Cubana von Grund auf lernen: Grundschritt, Partnerarbeit, einfache Figuren und Musikalität. Tanzen mit oder ohne Partner willkommen.',
   'ACTIVE', TRUE, 'Julia Braun',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=julia',
   NOW() + INTERVAL '3 days',
   6, 1, 90, ARRAY[5], 'EVENING', 16, 'PUBLIC',
   'Grundschritte und erste Figuren der Salsa Cubana beherrschen.',
   'Keine Tanzerfahrung nötig. Bequeme Schuhe mitbringen.',
   'IN_PERSON', NOW() - INTERVAL '35 days', NOW() - INTERVAL '4 hours'),

  ('40000000-0000-0000-0000-000000000009',
   '30000000-0000-0000-0000-000000000009',
   'Gravel-Tour Fränkische Schweiz',
   'Geführte Gravel-Touren durch die fränkische Schweiz. Verschiedene Streckenlängen (40–80 km), Schotterpisten, Panoramarouten und gemütliche Einkehr.',
   'ACTIVE', TRUE, 'Markus Fischer',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=markus',
   NOW() + INTERVAL '7 days',
   8, 1, 240, ARRAY[6], 'MORNING', 10, 'PUBLIC',
   'Ausdauer für Mehrstunden-Touren aufbauen. Fränkische Schweiz auf Schotter erkunden.',
   'Gravel- oder Mountainbike erforderlich. 30 km Radfahren ohne Pause.',
   'IN_PERSON', NOW() - INTERVAL '22 days', NOW() - INTERVAL '2 hours'),

  ('40000000-0000-0000-0000-000000000010',
   '30000000-0000-0000-0000-000000000010',
   'Pilates & Barre Intensivkurs Bremen',
   'Kombination aus klassischem Pilates und Barre-Elementen. Stärkung der tiefen Bauch- und Rückenmuskulatur, Verbesserung der Körperhaltung und Flexibilität.',
   'ACTIVE', TRUE, 'Sarah Richter',
   'https://api.dicebear.com/7.x/avataaars/svg?seed=sarah',
   NOW() + INTERVAL '2 days',
   10, 2, 60, ARRAY[1,4], 'MORNING', 8, 'PUBLIC',
   'Körperhaltung verbessern. Rückenschmerzen reduzieren und Körperkern stärken.',
   'Keine Vorkenntnisse nötig. Sportkleidung und Gymnastikmatte empfohlen.',
   'IN_PERSON', NOW() - INTERVAL '18 days', NOW() - INTERVAL '6 hours');

-- ============================================================
-- 6. SCHEDULES (10 rows, one per program)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting schedules...'; END $$;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public,
    show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
  ('50000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000001',
   'Volkspark Friedrichshain', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(13.4336, 52.5273), 4326),
   'Am Volkspark 1, 10243 Berlin', TRUE,
   NOW() + INTERVAL '3 days', NOW() + INTERVAL '3 days' + INTERVAL '75 minutes',
   'FREQ=WEEKLY;BYDAY=MO,WE,FR,SA', 8, NOW() - INTERVAL '58 days'),

  ('50000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002',
   'Yoga-Studio Schwabing', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(11.5740, 48.1630), 4326),
   'Leopoldstraße 42, 80802 München', TRUE,
   NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days' + INTERVAL '60 minutes',
   'FREQ=WEEKLY;BYDAY=TU,TH', 10, NOW() - INTERVAL '53 days'),

  ('50000000-0000-0000-0000-000000000003',
   '40000000-0000-0000-0000-000000000003',
   'Alsterufer Hamburg', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(9.9979, 53.5659), 4326),
   'Alsterufer 1, 20354 Hamburg', TRUE,
   NOW() + INTERVAL '4 days', NOW() + INTERVAL '4 days' + INTERVAL '60 minutes',
   'FREQ=WEEKLY;BYDAY=TU,TH,SA', 6, NOW() - INTERVAL '48 days'),

  ('50000000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000004',
   'Kletterzentrum Köln', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(6.9441, 50.9226), 4326),
   'Schanzenstraße 6-20, 51063 Köln', TRUE,
   NOW() + INTERVAL '5 days', NOW() + INTERVAL '5 days' + INTERVAL '120 minutes',
   'FREQ=WEEKLY;BYDAY=WE,SA', 8, NOW() - INTERVAL '43 days'),

  ('50000000-0000-0000-0000-000000000005',
   '40000000-0000-0000-0000-000000000005',
   'Sportpark Eschborn Frankfurt', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(8.5710, 50.1439), 4326),
   'Sossenheimer Straße 20, 65760 Eschborn', TRUE,
   NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day' + INTERVAL '45 minutes',
   'FREQ=WEEKLY;BYDAY=MO,WE,FR', 12, NOW() - INTERVAL '38 days'),

  ('50000000-0000-0000-0000-000000000006',
   '40000000-0000-0000-0000-000000000006',
   'Hallenbad Zuffenhausen Stuttgart', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(9.1611, 48.8283), 4326),
   'Schozacher Straße 26, 70435 Stuttgart', TRUE,
   NOW() + INTERVAL '6 days', NOW() + INTERVAL '6 days' + INTERVAL '90 minutes',
   'FREQ=WEEKLY;BYDAY=TU,TH,SU', 8, NOW() - INTERVAL '36 days'),

  ('50000000-0000-0000-0000-000000000007',
   '40000000-0000-0000-0000-000000000007',
   'Kampfsportzentrum Düsseldorf', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(6.7872, 51.2290), 4326),
   'Graf-Recke-Straße 82, 40239 Düsseldorf', TRUE,
   NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days' + INTERVAL '60 minutes',
   'FREQ=WEEKLY;BYDAY=TU,FR', 10, NOW() - INTERVAL '28 days'),

  ('50000000-0000-0000-0000-000000000008',
   '40000000-0000-0000-0000-000000000008',
   'Tanzschule Leipzig-Mitte', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(12.3810, 51.3406), 4326),
   'Karl-Liebknecht-Straße 66, 04275 Leipzig', TRUE,
   NOW() + INTERVAL '3 days', NOW() + INTERVAL '3 days' + INTERVAL '90 minutes',
   'FREQ=WEEKLY;BYDAY=FR', 16, NOW() - INTERVAL '33 days'),

  ('50000000-0000-0000-0000-000000000009',
   '40000000-0000-0000-0000-000000000009',
   'Treffpunkt Bahnhof Ebermannstadt', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(11.1800, 49.7800), 4326),
   'Bahnhofstraße 1, 91320 Ebermannstadt', TRUE,
   NOW() + INTERVAL '7 days', NOW() + INTERVAL '7 days' + INTERVAL '240 minutes',
   'FREQ=WEEKLY;BYDAY=SA', 10, NOW() - INTERVAL '20 days'),

  ('50000000-0000-0000-0000-000000000010',
   '40000000-0000-0000-0000-000000000010',
   'Sportstudio Bremen Mitte', 'PUBLIC',
   ST_SetSRID(ST_MakePoint(8.8025, 53.0758), 4326),
   'Sögestraße 21, 28195 Bremen', TRUE,
   NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days' + INTERVAL '60 minutes',
   'FREQ=WEEKLY;BYDAY=MO,TH', 8, NOW() - INTERVAL '16 days');

-- ============================================================
-- 7. PROGRAM_MEDIA (10 rows, one per program)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting program_media...'; END $$;

INSERT INTO program_media (id, program_id, url, media_type, sort_order, created_at)
VALUES
  ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
   'https://images.unsplash.com/photo-1608138404239-d2e0b8d4e4c7?w=800', 'IMAGE', 0, NOW() - INTERVAL '58 days'),
  ('60000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002',
   'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800', 'IMAGE', 0, NOW() - INTERVAL '53 days'),
  ('60000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000003',
   'https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=800', 'IMAGE', 0, NOW() - INTERVAL '48 days'),
  ('60000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000004',
   'https://images.unsplash.com/photo-1522163182402-834f871fd851?w=800', 'IMAGE', 0, NOW() - INTERVAL '43 days'),
  ('60000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000005',
   'https://images.unsplash.com/photo-1571019613454-1cb2f99b2d8b?w=800', 'IMAGE', 0, NOW() - INTERVAL '38 days'),
  ('60000000-0000-0000-0000-000000000006', '40000000-0000-0000-0000-000000000006',
   'https://images.unsplash.com/photo-1530549387789-4c1017266635?w=800', 'IMAGE', 0, NOW() - INTERVAL '36 days'),
  ('60000000-0000-0000-0000-000000000007', '40000000-0000-0000-0000-000000000007',
   'https://images.unsplash.com/photo-1555597673-b21d5c935865?w=800', 'IMAGE', 0, NOW() - INTERVAL '28 days'),
  ('60000000-0000-0000-0000-000000000008', '40000000-0000-0000-0000-000000000008',
   'https://images.unsplash.com/photo-1504609813442-a8924e83f76e?w=800', 'IMAGE', 0, NOW() - INTERVAL '33 days'),
  ('60000000-0000-0000-0000-000000000009', '40000000-0000-0000-0000-000000000009',
   'https://images.unsplash.com/photo-1517649763962-0c623066013b?w=800', 'IMAGE', 0, NOW() - INTERVAL '20 days'),
  ('60000000-0000-0000-0000-000000000010', '40000000-0000-0000-0000-000000000010',
   'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800', 'IMAGE', 0, NOW() - INTERVAL '16 days');

-- ============================================================
-- 8. CONVERSATIONS (10 rows)
-- Direct conversations between pairs of users
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting conversations...'; END $$;

INSERT INTO conversations (id, type, activity_context_id, created_at, last_message_at)
VALUES
  ('70000000-0000-0000-0000-000000000001', 'DIRECT', '20000000-0000-0000-0000-000000000001', NOW() - INTERVAL '50 days', NOW() - INTERVAL '1 day'),
  ('70000000-0000-0000-0000-000000000002', 'DIRECT', '20000000-0000-0000-0000-000000000002', NOW() - INTERVAL '48 days', NOW() - INTERVAL '2 days'),
  ('70000000-0000-0000-0000-000000000003', 'DIRECT', '20000000-0000-0000-0000-000000000003', NOW() - INTERVAL '45 days', NOW() - INTERVAL '3 days'),
  ('70000000-0000-0000-0000-000000000004', 'DIRECT', '20000000-0000-0000-0000-000000000004', NOW() - INTERVAL '40 days', NOW() - INTERVAL '4 days'),
  ('70000000-0000-0000-0000-000000000005', 'DIRECT', '20000000-0000-0000-0000-000000000005', NOW() - INTERVAL '35 days', NOW() - INTERVAL '5 days'),
  ('70000000-0000-0000-0000-000000000006', 'DIRECT', '20000000-0000-0000-0000-000000000006', NOW() - INTERVAL '30 days', NOW() - INTERVAL '6 days'),
  ('70000000-0000-0000-0000-000000000007', 'DIRECT', '20000000-0000-0000-0000-000000000007', NOW() - INTERVAL '25 days', NOW() - INTERVAL '7 days'),
  ('70000000-0000-0000-0000-000000000008', 'DIRECT', '20000000-0000-0000-0000-000000000008', NOW() - INTERVAL '20 days', NOW() - INTERVAL '8 days'),
  ('70000000-0000-0000-0000-000000000009', 'DIRECT', '20000000-0000-0000-0000-000000000009', NOW() - INTERVAL '15 days', NOW() - INTERVAL '9 days'),
  ('70000000-0000-0000-0000-000000000010', 'DIRECT', '20000000-0000-0000-0000-000000000010', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days');

-- ============================================================
-- 9. CONVERSATION_MEMBERS (2 members per conversation = 20 rows)
-- conv 1: user1↔user2, conv2: user2↔user3, ..., conv10: user10↔user1
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting conversation_members...'; END $$;

INSERT INTO conversation_members (conversation_id, user_id, joined_at, last_read_at)
VALUES
  ('70000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001', NOW()-INTERVAL '50 days', NOW()-INTERVAL '1 day'),
  ('70000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002', NOW()-INTERVAL '50 days', NOW()-INTERVAL '2 days'),
  ('70000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002', NOW()-INTERVAL '48 days', NOW()-INTERVAL '2 days'),
  ('70000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003', NOW()-INTERVAL '48 days', NOW()-INTERVAL '3 days'),
  ('70000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000003', NOW()-INTERVAL '45 days', NOW()-INTERVAL '3 days'),
  ('70000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004', NOW()-INTERVAL '45 days', NOW()-INTERVAL '4 days'),
  ('70000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000004', NOW()-INTERVAL '40 days', NOW()-INTERVAL '4 days'),
  ('70000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000005', NOW()-INTERVAL '40 days', NOW()-INTERVAL '5 days'),
  ('70000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000005', NOW()-INTERVAL '35 days', NOW()-INTERVAL '5 days'),
  ('70000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006', NOW()-INTERVAL '35 days', NOW()-INTERVAL '6 days'),
  ('70000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000006', NOW()-INTERVAL '30 days', NOW()-INTERVAL '6 days'),
  ('70000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000007', NOW()-INTERVAL '30 days', NOW()-INTERVAL '7 days'),
  ('70000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000007', NOW()-INTERVAL '25 days', NOW()-INTERVAL '7 days'),
  ('70000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008', NOW()-INTERVAL '25 days', NOW()-INTERVAL '8 days'),
  ('70000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000008', NOW()-INTERVAL '20 days', NOW()-INTERVAL '8 days'),
  ('70000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000009', NOW()-INTERVAL '20 days', NOW()-INTERVAL '9 days'),
  ('70000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000009', NOW()-INTERVAL '15 days', NOW()-INTERVAL '9 days'),
  ('70000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000010', NOW()-INTERVAL '15 days', NOW()-INTERVAL '10 days'),
  ('70000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000010', NOW()-INTERVAL '10 days', NOW()-INTERVAL '10 days'),
  ('70000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000001', NOW()-INTERVAL '10 days', NOW()-INTERVAL '10 days');

-- ============================================================
-- 10. MESSAGES (10 rows, one per conversation)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting messages...'; END $$;

INSERT INTO messages (id, conversation_id, sender_id, content, status, sent_at, read_at)
VALUES
  ('80000000-0000-0000-0000-000000000001',
   '70000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000001',
   'Hallo Lena! Ich habe dein Yogaprogramm entdeckt und finde es super interessant. Kannst du mir mehr über den Ablauf erzählen?',
   'READ', NOW()-INTERVAL '1 day', NOW()-INTERVAL '23 hours'),

  ('80000000-0000-0000-0000-000000000002',
   '70000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000002',
   'Hallo Max! Ich habe gesehen, dass du auch gerne läufst. Hättest du Lust, gemeinsam am Wochenende eine Runde an der Alster zu drehen?',
   'READ', NOW()-INTERVAL '2 days', NOW()-INTERVAL '1 day' + INTERVAL '12 hours'),

  ('80000000-0000-0000-0000-000000000003',
   '70000000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000003',
   'Hi Anna! Dein Boulderprogramm klingt genau nach meinem Geschmack. Welcher Grad wird hauptsächlich trainiert?',
   'DELIVERED', NOW()-INTERVAL '3 days', NULL),

  ('80000000-0000-0000-0000-000000000004',
   '70000000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000004',
   'Hallo Felix! Ich interessiere mich für dein Bootcamp. Bin Anfänger — ist das auch für Einsteiger geeignet?',
   'READ', NOW()-INTERVAL '4 days', NOW()-INTERVAL '3 days' + INTERVAL '8 hours'),

  ('80000000-0000-0000-0000-000000000005',
   '70000000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000005',
   'Hi Sophie! Tolles Schwimmprogramm. Ich bereite mich auf meinen ersten Triathlon vor — genau was ich gesucht habe!',
   'READ', NOW()-INTERVAL '5 days', NOW()-INTERVAL '4 days' + INTERVAL '6 hours'),

  ('80000000-0000-0000-0000-000000000006',
   '70000000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000006',
   'Hallo Tobias! Ich möchte mit Kickboxen anfangen. Hast du noch Plätze frei in deinem Kurs?',
   'READ', NOW()-INTERVAL '6 days', NOW()-INTERVAL '5 days' + INTERVAL '4 hours'),

  ('80000000-0000-0000-0000-000000000007',
   '70000000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000007',
   'Hallo Julia! Ich liebe Salsa! Wann startet der nächste Anfängerkurs und wie viel kostet er?',
   'READ', NOW()-INTERVAL '7 days', NOW()-INTERVAL '6 days' + INTERVAL '2 hours'),

  ('80000000-0000-0000-0000-000000000008',
   '70000000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000008',
   'Hey Markus! Die Gravel-Tour klingt fantastisch. Welche Streckenlänge empfiehlst du für Einsteiger?',
   'DELIVERED', NOW()-INTERVAL '8 days', NULL),

  ('80000000-0000-0000-0000-000000000009',
   '70000000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000009',
   'Hallo Sarah! Ich habe Rückenbeschwerden und suche etwas Sanftes. Ist Pilates bei dir geeignet?',
   'READ', NOW()-INTERVAL '9 days', NOW()-INTERVAL '8 days' + INTERVAL '10 hours'),

  ('80000000-0000-0000-0000-000000000010',
   '70000000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000010',
   'Hi Seyd! Ich habe deinen Marathon-Trainingsplan gesehen. Du scheinst sehr erfahren zu sein — hast du Tipps für die langen Läufe?',
   'READ', NOW()-INTERVAL '10 days', NOW()-INTERVAL '9 days' + INTERVAL '5 hours');

-- ============================================================
-- 11. USER_PROGRAMS (10 rows — users enrolling in others' programs)
-- user N+1 joins program N (no self-enrollment)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting user_programs...'; END $$;

INSERT INTO user_programs (id, user_id, program_id, schedule_id, status,
    leave_reason, progress_percentage, activities_completed, activities_skipped,
    last_activity_at, joined_at, left_at)
VALUES
  ('90000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000001',
   'ACTIVE', NULL, 35, 14, 2, NOW()-INTERVAL '2 days', NOW()-INTERVAL '55 days', NULL),

  ('90000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000003',
   '40000000-0000-0000-0000-000000000002',
   '50000000-0000-0000-0000-000000000002',
   'ACTIVE', NULL, 50, 8, 0, NOW()-INTERVAL '1 day', NOW()-INTERVAL '45 days', NULL),

  ('90000000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000003',
   '50000000-0000-0000-0000-000000000003',
   'ACTIVE', NULL, 25, 6, 1, NOW()-INTERVAL '3 days', NOW()-INTERVAL '40 days', NULL),

  ('90000000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000005',
   '40000000-0000-0000-0000-000000000004',
   '50000000-0000-0000-0000-000000000004',
   'ACTIVE', NULL, 60, 12, 0, NOW()-INTERVAL '1 day', NOW()-INTERVAL '38 days', NULL),

  ('90000000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000006',
   '40000000-0000-0000-0000-000000000005',
   '50000000-0000-0000-0000-000000000005',
   'ACTIVE', NULL, 40, 7, 1, NOW()-INTERVAL '5 hours', NOW()-INTERVAL '30 days', NULL),

  ('90000000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000007',
   '40000000-0000-0000-0000-000000000006',
   '50000000-0000-0000-0000-000000000006',
   'ACTIVE', NULL, 55, 9, 0, NOW()-INTERVAL '6 days', NOW()-INTERVAL '28 days', NULL),

  ('90000000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000008',
   '40000000-0000-0000-0000-000000000007',
   '50000000-0000-0000-0000-000000000007',
   'ACTIVE', NULL, 30, 5, 2, NOW()-INTERVAL '2 days', NOW()-INTERVAL '22 days', NULL),

  ('90000000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000009',
   '40000000-0000-0000-0000-000000000008',
   '50000000-0000-0000-0000-000000000008',
   'ACTIVE', NULL, 83, 5, 0, NOW()-INTERVAL '3 days', NOW()-INTERVAL '18 days', NULL),

  ('90000000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000010',
   '40000000-0000-0000-0000-000000000009',
   '50000000-0000-0000-0000-000000000009',
   'ACTIVE', NULL, 12, 1, 0, NOW()-INTERVAL '7 days', NOW()-INTERVAL '12 days', NULL),

  ('90000000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000010',
   '50000000-0000-0000-0000-000000000010',
   'ACTIVE', NULL, 70, 7, 0, NOW()-INTERVAL '2 days', NOW()-INTERVAL '15 days', NULL);

-- ============================================================
-- 12. PROGRAM_ACTIVITIES (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting program_activities...'; END $$;

INSERT INTO program_activities (id, user_program_id, activity_id, status, completed_at, skipped_at, notes)
VALUES
  ('A1000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'COMPLETED', NOW()-INTERVAL '20 days', NULL, 'Erste Einheit — 8 km Grundlage. Gut gelaufen!'),
  ('A1000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'COMPLETED', NOW()-INTERVAL '15 days', NULL, 'Surya Namaskar und Grundasanas geübt.'),
  ('A1000000-0000-0000-0000-000000000003', '90000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', 'COMPLETED', NOW()-INTERVAL '18 days', NULL, 'Tempolauf 5×1 km — persönliche Bestzeit!'),
  ('A1000000-0000-0000-0000-000000000004', '90000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000003', 'SKIPPED',   NULL, NOW()-INTERVAL '10 days', 'Wegen Krankheit ausgefallen.'),
  ('A1000000-0000-0000-0000-000000000005', '90000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000004', 'COMPLETED', NOW()-INTERVAL '5 hours',  NULL, 'HIIT-Session abgeschlossen. Sehr anstrengend!'),
  ('A1000000-0000-0000-0000-000000000006', '90000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006', 'COMPLETED', NOW()-INTERVAL '6 days',  NULL, '2.000 m Kraul in 42 Minuten.'),
  ('A1000000-0000-0000-0000-000000000007', '90000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000007', 'PENDING',   NULL, NULL, NULL),
  ('A1000000-0000-0000-0000-000000000008', '90000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000008', 'COMPLETED', NOW()-INTERVAL '3 days',  NULL, 'Grundschritt und erste Drehfigur gelernt.'),
  ('A1000000-0000-0000-0000-000000000009', '90000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000005', 'PENDING',   NULL, NULL, NULL),
  ('A1000000-0000-0000-0000-000000000010', '90000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000002', 'COMPLETED', NOW()-INTERVAL '2 days',  NULL, 'Pilates-Grundübungen — Körperspannung deutlich verbessert.');

-- ============================================================
-- 13. REVIEWS (10 rows — reviewer is always the enrolled user)
-- interaction_proof_id references the corresponding conversation
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting reviews...'; END $$;

INSERT INTO reviews (id, program_id, reviewer_id, interaction_proof_id, score, comment, created_at)
VALUES
  ('B1000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
   '70000000-0000-0000-0000-000000000001', 4.5,
   'Super Trainingsplan! Seyd ist sehr motivierend und passt die Einheiten individuell an. Die Gruppenatmosphäre ist fantastisch.',
   NOW()-INTERVAL '15 days'),

  ('B1000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
   '70000000-0000-0000-0000-000000000002', 5.0,
   'Lena ist eine außergewöhnliche Lehrerin. Ich hatte noch nie Yoga gemacht und fühle mich jetzt viel wohler in meinem Körper.',
   NOW()-INTERVAL '20 days'),

  ('B1000000-0000-0000-0000-000000000003',
   '40000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004',
   '70000000-0000-0000-0000-000000000003', 4.0,
   'Guter Laufplan, strukturiert und abwechslungsreich. Das Tempo war manchmal etwas hoch für mich.',
   NOW()-INTERVAL '18 days'),

  ('B1000000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000005',
   '70000000-0000-0000-0000-000000000004', 4.8,
   'Anna erklärt die Klettertechnik sehr detailliert. Das Niveau ist genau richtig für Fortgeschrittene.',
   NOW()-INTERVAL '16 days'),

  ('B1000000-0000-0000-0000-000000000005',
   '40000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000006',
   '70000000-0000-0000-0000-000000000005', 4.2,
   'Intensives Bootcamp, Felix kennt sich wirklich aus. Die Übungen sind variiert und effektiv.',
   NOW()-INTERVAL '14 days'),

  ('B1000000-0000-0000-0000-000000000006',
   '40000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000007',
   '70000000-0000-0000-0000-000000000006', 4.7,
   'Sophie ist sehr professionell und bringt echte Wettkampferfahrung mit. Das Training ist auf hohem Niveau.',
   NOW()-INTERVAL '12 days'),

  ('B1000000-0000-0000-0000-000000000007',
   '40000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000008',
   '70000000-0000-0000-0000-000000000007', 5.0,
   'Tobias ist ein geduldig erklärender Trainer. Als absolute Anfängerin hatte ich keine Angst und habe schnell Fortschritte gemacht.',
   NOW()-INTERVAL '10 days'),

  ('B1000000-0000-0000-0000-000000000008',
   '40000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000009',
   '70000000-0000-0000-0000-000000000008', 4.5,
   'Julias Unterrichtsstil ist ansteckend. Der Kurs macht riesigen Spaß und die Musik ist toll ausgewählt.',
   NOW()-INTERVAL '8 days'),

  ('B1000000-0000-0000-0000-000000000009',
   '40000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000010',
   '70000000-0000-0000-0000-000000000009', 4.3,
   'Markus kennt die Routen perfekt. Wunderschöne Strecken, gut organisiert. Empfehle es sehr!',
   NOW()-INTERVAL '6 days'),

  ('B1000000-0000-0000-0000-000000000010',
   '40000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
   '70000000-0000-0000-0000-000000000010', 5.0,
   'Sarah hat mir bei meinen Rückenproblemen wirklich geholfen. Die Kombination aus Pilates und Barre ist sehr effektiv.',
   NOW()-INTERVAL '4 days');

-- ============================================================
-- 14. REVIEW_CRITERIA (10 rows — one criterion set per review)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting review_criteria...'; END $$;

INSERT INTO review_criteria (id, review_id, criterion_key, score) VALUES
  ('C1000000-0000-0000-0000-000000000001', 'B1000000-0000-0000-0000-000000000001', 'ponctualite',  4.5),
  ('C1000000-0000-0000-0000-000000000002', 'B1000000-0000-0000-0000-000000000002', 'pedagogie',    5.0),
  ('C1000000-0000-0000-0000-000000000003', 'B1000000-0000-0000-0000-000000000003', 'progression',  4.0),
  ('C1000000-0000-0000-0000-000000000004', 'B1000000-0000-0000-0000-000000000004', 'securite',     4.8),
  ('C1000000-0000-0000-0000-000000000005', 'B1000000-0000-0000-0000-000000000005', 'intensite',    4.2),
  ('C1000000-0000-0000-0000-000000000006', 'B1000000-0000-0000-0000-000000000006', 'pedagogie',    4.7),
  ('C1000000-0000-0000-0000-000000000007', 'B1000000-0000-0000-0000-000000000007', 'ambiance',     5.0),
  ('C1000000-0000-0000-0000-000000000008', 'B1000000-0000-0000-0000-000000000008', 'ambiance',     4.5),
  ('C1000000-0000-0000-0000-000000000009', 'B1000000-0000-0000-0000-000000000009', 'organisation', 4.3),
  ('C1000000-0000-0000-0000-000000000010', 'B1000000-0000-0000-0000-000000000010', 'pedagogie',    5.0);

-- ============================================================
-- 15. PEER_RECOMMENDATIONS (10 rows)
-- Uses real column names from V18: recommender_id, recommended_id, conversation_id
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting peer_recommendations...'; END $$;

INSERT INTO peer_recommendations (id, recommender_id, recommended_id, conversation_id,
    rating, comment, activity_context, program_context, created_at, updated_at)
VALUES
  ('D1000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
   '70000000-0000-0000-0000-000000000001', 5,
   'Seyd ist ein sehr motivierter Läufer und toller Trainingspartner. Absolut empfehlenswert!',
   '20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
   NOW()-INTERVAL '14 days', NOW()-INTERVAL '14 days'),

  ('D1000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
   '70000000-0000-0000-0000-000000000002', 5,
   'Lena ist eine außergewöhnliche Yogalehrerin. Geduldig, präzise und sehr einfühlsam.',
   '20000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002',
   NOW()-INTERVAL '19 days', NOW()-INTERVAL '19 days'),

  ('D1000000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003',
   '70000000-0000-0000-0000-000000000003', 4,
   'Max ist sehr engagiert und hilfsbereit. Hat mir viele Tipps für meine Lauftechnik gegeben.',
   '20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000003',
   NOW()-INTERVAL '17 days', NOW()-INTERVAL '17 days'),

  ('D1000000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000004',
   '70000000-0000-0000-0000-000000000004', 5,
   'Anna ist eine leidenschaftliche Klettererin, die ihr Wissen gerne teilt. Top Trainingspartnerin!',
   '20000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000004',
   NOW()-INTERVAL '15 days', NOW()-INTERVAL '15 days'),

  ('D1000000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000005',
   '70000000-0000-0000-0000-000000000005', 4,
   'Felix weiß genau, wie man Leute pusht ohne zu überfordern. Sehr kompetenter Trainer.',
   '20000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000005',
   NOW()-INTERVAL '13 days', NOW()-INTERVAL '13 days'),

  ('D1000000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000006',
   '70000000-0000-0000-0000-000000000006', 5,
   'Sophie ist eine echte Profi-Athletin und gibt ihr Wissen hervorragend weiter.',
   '20000000-0000-0000-0000-000000000006', '40000000-0000-0000-0000-000000000006',
   NOW()-INTERVAL '11 days', NOW()-INTERVAL '11 days'),

  ('D1000000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000007',
   '70000000-0000-0000-0000-000000000007', 5,
   'Tobias hat mir als Anfängerin das Kickboxen richtig schmackhaft gemacht. Super Trainer!',
   '20000000-0000-0000-0000-000000000007', '40000000-0000-0000-0000-000000000007',
   NOW()-INTERVAL '9 days', NOW()-INTERVAL '9 days'),

  ('D1000000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000008',
   '70000000-0000-0000-0000-000000000008', 4,
   'Julia bringt so viel Energie in den Kurs. Man lernt nicht nur tanzen, sondern hat auch super Spaß.',
   '20000000-0000-0000-0000-000000000008', '40000000-0000-0000-0000-000000000008',
   NOW()-INTERVAL '7 days', NOW()-INTERVAL '7 days'),

  ('D1000000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000009',
   '70000000-0000-0000-0000-000000000009', 5,
   'Markus kennt die fränkische Schweiz wie seine eigene Westentasche. Unvergessliche Touren!',
   '20000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000009',
   NOW()-INTERVAL '5 days', NOW()-INTERVAL '5 days'),

  ('D1000000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000010',
   '70000000-0000-0000-0000-000000000010', 5,
   'Sarah hat mir wirklich geholfen meine Körperhaltung zu verbessern. Sehr empfehlenswert!',
   '20000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000010',
   NOW()-INTERVAL '3 days', NOW()-INTERVAL '3 days');

-- ============================================================
-- 16. BADGES (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting badges...'; END $$;

INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
  ('E1000000-0000-0000-0000-000000000001', 'FIRST_PROGRAM',     'CREATION',    'Erstes Programm',        'PROGRAMS_CREATED',         1,  'rocket_launch'),
  ('E1000000-0000-0000-0000-000000000002', 'ACTIVE_COACH',      'CREATION',    'Aktiver Coach',          'PROGRAMS_CREATED',         5,  'school'),
  ('E1000000-0000-0000-0000-000000000003', 'SOCIAL_BUTTERFLY',  'SOCIAL',      'Sozialer Schmetterling', 'RECOMMENDATIONS_RECEIVED',  3,  'emoji_people'),
  ('E1000000-0000-0000-0000-000000000004', 'TOP_RATED',         'REPUTATION',  'Top Bewertet',           'AVERAGE_REVIEW_SCORE',      4,  'star'),
  ('E1000000-0000-0000-0000-000000000005', 'MARATHON_RUNNER',   'ACTIVITY',    'Marathonläufer',         'ACTIVITIES_COMPLETED',     42,  'directions_run'),
  ('E1000000-0000-0000-0000-000000000006', 'EARLY_BIRD',        'ACTIVITY',    'Frühaufsteher',          'MORNING_SESSIONS',          7,  'wb_sunny'),
  ('E1000000-0000-0000-0000-000000000007', 'TEAM_PLAYER',       'SOCIAL',      'Teamplayer',             'GROUP_ENROLLMENTS',         5,  'groups'),
  ('E1000000-0000-0000-0000-000000000008', 'PERFECT_SCORE',     'REPUTATION',  'Perfekte Bewertung',     'PERFECT_REVIEWS',           3,  'grade'),
  ('E1000000-0000-0000-0000-000000000009', 'DEDICATED',         'ACTIVITY',    'Engagiert',              'STREAK_DAYS',              14,  'local_fire_department'),
  ('E1000000-0000-0000-0000-000000000010', 'EXPLORER',          'ACTIVITY',    'Entdecker',              'UNIQUE_ACTIVITIES',          3,  'explore');

-- ============================================================
-- 17. BADGE_AWARDS (10 rows — one badge per user)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting badge_awards...'; END $$;

INSERT INTO badge_awards (badge_id, user_id, awarded_at) VALUES
  ('E1000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NOW()-INTERVAL '58 days'),
  ('E1000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', NOW()-INTERVAL '50 days'),
  ('E1000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003', NOW()-INTERVAL '45 days'),
  ('E1000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004', NOW()-INTERVAL '40 days'),
  ('E1000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005', NOW()-INTERVAL '35 days'),
  ('E1000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006', NOW()-INTERVAL '30 days'),
  ('E1000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007', NOW()-INTERVAL '25 days'),
  ('E1000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008', NOW()-INTERVAL '20 days'),
  ('E1000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009', NOW()-INTERVAL '15 days'),
  ('E1000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010', NOW()-INTERVAL '10 days');

-- ============================================================
-- 18. NOTIFICATIONS (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting notifications...'; END $$;

INSERT INTO notifications (id, user_id, type, channel, payload, is_read, sent_at, read_at)
VALUES
  ('F1000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'NEW_REVIEW', 'IN_APP',
   '{"programId":"40000000-0000-0000-0000-000000000001","reviewerName":"Lena Müller","score":4.5}',
   TRUE, NOW()-INTERVAL '15 days', NOW()-INTERVAL '15 days' + INTERVAL '30 minutes'),

  ('F1000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
   'NEW_PEER_REC', 'IN_APP',
   '{"fromUserId":"00000000-0000-0000-0000-000000000003","fromUserName":"Max Schmidt"}',
   TRUE, NOW()-INTERVAL '19 days', NOW()-INTERVAL '18 days'),

  ('F1000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003',
   'NEW_MESSAGE', 'PUSH',
   '{"conversationId":"70000000-0000-0000-0000-000000000002","senderName":"Lena Müller"}',
   FALSE, NOW()-INTERVAL '2 days', NULL),

  ('F1000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004',
   'PROGRAM_REMINDER', 'PUSH',
   '{"programId":"40000000-0000-0000-0000-000000000003","programTitle":"Halbmarathon Hamburg","sessionAt":"2026-07-13T08:00:00Z"}'::jsonb,
   FALSE, NOW()-INTERVAL '1 day', NULL),

  ('F1000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005',
   'NEW_BADGE', 'IN_APP',
   '{"badgeCode":"MARATHON_RUNNER","badgeLabel":"Marathonläufer"}',
   TRUE, NOW()-INTERVAL '35 days', NOW()-INTERVAL '35 days' + INTERVAL '1 hour'),

  ('F1000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006',
   'NEW_REVIEW', 'IN_APP',
   '{"programId":"40000000-0000-0000-0000-000000000006","reviewerName":"Tobias Wagner","score":4.7}',
   TRUE, NOW()-INTERVAL '12 days', NOW()-INTERVAL '12 days' + INTERVAL '45 minutes'),

  ('F1000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007',
   'NEW_MESSAGE', 'IN_APP',
   '{"conversationId":"70000000-0000-0000-0000-000000000006","senderName":"Sophie Hoffmann"}',
   FALSE, NOW()-INTERVAL '6 days', NULL),

  ('F1000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008',
   'NEW_PEER_REC', 'PUSH',
   '{"fromUserId":"00000000-0000-0000-0000-000000000009","fromUserName":"Markus Fischer"}',
   FALSE, NOW()-INTERVAL '7 days', NULL),

  ('F1000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009',
   'PROGRAM_REMINDER', 'PUSH',
   '{"programId":"40000000-0000-0000-0000-000000000009","programTitle":"Gravel-Tour Fränkische Schweiz","sessionAt":"2026-07-16T08:00:00Z"}'::jsonb,
   FALSE, NOW()-INTERVAL '3 days', NULL),

  ('F1000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010',
   'NEW_BADGE', 'IN_APP',
   '{"badgeCode":"EXPLORER","badgeLabel":"Entdecker"}',
   TRUE, NOW()-INTERVAL '10 days', NOW()-INTERVAL '10 days' + INTERVAL '20 minutes');

-- ============================================================
-- 19. NOTIFICATION_PREFS (10 rows — one per user)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting notification_prefs...'; END $$;

INSERT INTO notification_prefs (id, user_id, notification_type, email_enabled, push_enabled, frequency)
VALUES
  ('11100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'NEW_MESSAGE',      TRUE,  TRUE,  'IMMEDIATE'),
  ('11100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'NEW_REVIEW',       TRUE,  TRUE,  'IMMEDIATE'),
  ('11100000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003', 'PROGRAM_REMINDER', TRUE,  TRUE,  'DAILY'),
  ('11100000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004', 'NEW_MESSAGE',      TRUE,  FALSE, 'DAILY'),
  ('11100000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005', 'NEW_BADGE',        FALSE, TRUE,  'IMMEDIATE'),
  ('11100000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006', 'NEW_PEER_REC',     TRUE,  TRUE,  'WEEKLY'),
  ('11100000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007', 'NEW_MESSAGE',      TRUE,  TRUE,  'IMMEDIATE'),
  ('11100000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008', 'NEW_REVIEW',       TRUE,  FALSE, 'DAILY'),
  ('11100000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009', 'PROGRAM_REMINDER', FALSE, TRUE,  'IMMEDIATE'),
  ('11100000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010', 'NEW_BADGE',        TRUE,  TRUE,  'IMMEDIATE');

-- ============================================================
-- 20. DEVICE_TOKENS (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting device_tokens...'; END $$;

INSERT INTO device_tokens (id, user_id, token, platform, device_name, created_at, last_used_at)
VALUES
  ('12100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'fGH3kL9mNpQr7sT2uVwX:APA91bA1Berlin001', 'ANDROID', 'Samsung Galaxy S23 — Berlin', NOW()-INTERVAL '85 days', NOW()-INTERVAL '1 hour'),
  ('12100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
   'aB4cD8eF6gH1iJ5kLmNo:APA91bA2Muenchen02', 'IOS', 'iPhone 15 Pro — München', NOW()-INTERVAL '75 days', NOW()-INTERVAL '2 hours'),
  ('12100000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003',
   'qR7sT0uV3wX6yZ9aB2cD:APA91bA3Hamburg003', 'ANDROID', 'Pixel 7 — Hamburg', NOW()-INTERVAL '65 days', NOW()-INTERVAL '3 hours'),
  ('12100000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004',
   'eF1gH4iJ7kL0mN3oP6qR:APA91bA4Koeln0004', 'IOS', 'iPhone 14 — Köln', NOW()-INTERVAL '55 days', NOW()-INTERVAL '30 minutes'),
  ('12100000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005',
   'sT4uV7wX0yZ3aB6cD9eF:APA91bA5Frankfurt5', 'ANDROID', 'OnePlus 12 — Frankfurt', NOW()-INTERVAL '45 days', NOW()-INTERVAL '5 minutes'),
  ('12100000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006',
   'gH8iJ1kL4mN7oP0qR3sT:APA91bA6Stuttgart6', 'IOS', 'iPhone 15 — Stuttgart', NOW()-INTERVAL '40 days', NOW()-INTERVAL '2 days'),
  ('12100000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007',
   'uV2wX5yZ8aB1cD4eF7gH:APA91bA7Duesseldrf', 'ANDROID', 'Samsung Galaxy A54 — Düsseldorf', NOW()-INTERVAL '30 days', NOW()-INTERVAL '1 day'),
  ('12100000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008',
   'iJ6kL9mN2oP5qR8sT1uV:APA91bA8Leipzig008', 'IOS', 'iPhone 13 — Leipzig', NOW()-INTERVAL '35 days', NOW()-INTERVAL '6 hours'),
  ('12100000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009',
   'wX3yZ6aB9cD2eF5gH8iJ:APA91bA9Nuernberg9', 'ANDROID', 'Xiaomi 13 — Nürnberg', NOW()-INTERVAL '25 days', NOW()-INTERVAL '4 hours'),
  ('12100000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010',
   'kL7mN0oP3qR6sT9uV2wX:APA91bA10Bremen010', 'WEB', 'Chrome — Bremen', NOW()-INTERVAL '20 days', NOW()-INTERVAL '30 minutes');

-- ============================================================
-- 21. REPORTS (10 rows — real column names from V18)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting reports...'; END $$;

INSERT INTO reports (id, reporter_id, reported_entity_type, reported_entity_id,
    reason, description, status, created_at, resolved_at, reviewed_by, reviewed_at, resolution_notes)
VALUES
  ('13100000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000001', 'PROGRAM',
   '40000000-0000-0000-0000-000000000007',
   'SPAM', 'Dieses Programm scheint identisch mit einem anderen Angebot zu sein.',
   'RESOLVED', NOW()-INTERVAL '25 days', NOW()-INTERVAL '20 days',
   '00000000-0000-0000-0000-000000000005', NOW()-INTERVAL '20 days',
   'Überprüft — kein Duplikat festgestellt. Signalement geschlossen.'),

  ('13100000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000003', 'MESSAGE',
   '80000000-0000-0000-0000-000000000004',
   'INAPPROPRIATE_CONTENT', 'Diese Nachricht enthält unangemessene Sprache.',
   'OPEN', NOW()-INTERVAL '5 days', NULL, NULL, NULL, NULL),

  ('13100000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000004', 'USER',
   '00000000-0000-0000-0000-000000000008',
   'FAKE_PROFILE', 'Das Profilbild scheint ein Stock-Foto zu sein.',
   'DISMISSED', NOW()-INTERVAL '30 days', NOW()-INTERVAL '28 days',
   '00000000-0000-0000-0000-000000000002', NOW()-INTERVAL '28 days',
   'Profilbild überprüft — kein Verstoß festgestellt.'),

  ('13100000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000005', 'PROGRAM',
   '40000000-0000-0000-0000-000000000003',
   'MISLEADING_INFORMATION',
   'Die angegebene Strecke ist deutlich länger als beschrieben.',
   'OPEN', NOW()-INTERVAL '3 days', NULL, NULL, NULL, NULL),

  ('13100000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000006', 'USER',
   '00000000-0000-0000-0000-000000000010',
   'SPAM', 'Dieser Nutzer hat mir unerwünschte Nachrichten gesendet.',
   'RESOLVED', NOW()-INTERVAL '20 days', NOW()-INTERVAL '18 days',
   '00000000-0000-0000-0000-000000000001', NOW()-INTERVAL '18 days',
   'Nutzer verwarnt. Wiederholung führt zur Sperrung.'),

  ('13100000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000007', 'PROGRAM',
   '40000000-0000-0000-0000-000000000002',
   'OTHER', 'Der Kursort hat sich geändert ohne Ankündigung.',
   'OPEN', NOW()-INTERVAL '2 days', NULL, NULL, NULL, NULL),

  ('13100000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000008', 'MESSAGE',
   '80000000-0000-0000-0000-000000000007',
   'HARASSMENT', 'Ich fühle mich durch diese Nachricht belästigt.',
   'RESOLVED', NOW()-INTERVAL '15 days', NOW()-INTERVAL '14 days',
   '00000000-0000-0000-0000-000000000003', NOW()-INTERVAL '14 days',
   'Nachricht entfernt. Absender verwarnt.'),

  ('13100000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000009', 'USER',
   '00000000-0000-0000-0000-000000000004',
   'OTHER', 'Dieser Nutzer hat wiederholt Termine ohne Absage nicht wahrgenommen.',
   'DISMISSED', NOW()-INTERVAL '10 days', NOW()-INTERVAL '9 days',
   '00000000-0000-0000-0000-000000000006', NOW()-INTERVAL '9 days',
   'Nicht ausreichend für eine Sanktion. Nutzer informiert.'),

  ('13100000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000010', 'PROGRAM',
   '40000000-0000-0000-0000-000000000005',
   'MISLEADING_INFORMATION',
   'Das Bootcamp wird als "Anfänger geeignet" beschrieben, ist aber sehr intensiv.',
   'OPEN', NOW()-INTERVAL '1 day', NULL, NULL, NULL, NULL),

  ('13100000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000002', 'USER',
   '00000000-0000-0000-0000-000000000007',
   'INAPPROPRIATE_CONTENT', 'Profilbio enthält anstößige Inhalte.',
   'OPEN', NOW()-INTERVAL '4 days', NULL, NULL, NULL, NULL);

-- ============================================================
-- 22. SEARCH_LOGS (10 rows — real column names from V18)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting search_logs...'; END $$;

INSERT INTO search_logs (id, user_id, raw_query, parsed_intent, results_count, search_method, searched_at)
VALUES
  ('14100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'Laufen Berlin Morgen',
   '{"activity":"laufen","city":"Berlin","timeOfDay":"morning","level":null}',
   8, 'fulltext', NOW()-INTERVAL '20 days'),

  ('14100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
   'Yoga München Anfänger',
   '{"activity":"yoga","city":"München","level":"BEGINNER","format":"GROUP"}',
   5, 'semantic', NOW()-INTERVAL '18 days'),

  ('14100000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003',
   'Halbmarathon Training Hamburg',
   '{"activity":"laufen","city":"Hamburg","distance":"21km","level":"INTERMEDIATE"}',
   3, 'fulltext', NOW()-INTERVAL '15 days'),

  ('14100000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004',
   'Bouldern Klettern Köln fortgeschritten',
   '{"activity":"klettern","city":"Köln","level":"ADVANCED","format":"GROUP"}',
   4, 'semantic', NOW()-INTERVAL '12 days'),

  ('14100000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005',
   'Fitness Bootcamp Frankfurt HIIT',
   '{"activity":"krafttraining","city":"Frankfurt","style":"HIIT","level":"ANY"}',
   6, 'fulltext', NOW()-INTERVAL '10 days'),

  ('14100000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006',
   'Schwimmen Triathlon Stuttgart',
   '{"activity":"schwimmen","city":"Stuttgart","goal":"triathlon","level":"ADVANCED"}',
   2, 'semantic', NOW()-INTERVAL '8 days'),

  ('14100000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007',
   'Kampfsport Kickboxen Düsseldorf',
   '{"activity":"kickboxen","city":"Düsseldorf","level":"ANY","format":"GROUP"}',
   3, 'fulltext', NOW()-INTERVAL '6 days'),

  ('14100000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008',
   'Salsa Tango Leipzig Paartanz',
   '{"activity":"tanzen","city":"Leipzig","style":"salsa","format":"DUO"}',
   4, 'semantic', NOW()-INTERVAL '5 days'),

  ('14100000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009',
   'Mountainbike Nürnberg Gravel',
   '{"activity":"mountainbike","city":"Nürnberg","style":"gravel","level":"INTERMEDIATE"}',
   2, 'fulltext', NOW()-INTERVAL '3 days'),

  ('14100000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010',
   'Pilates Bremen Rücken',
   '{"activity":"pilates","city":"Bremen","focus":"back","level":"BEGINNER"}',
   3, 'semantic', NOW()-INTERVAL '1 day');

-- ============================================================
-- 23. PROGRESSIONS (10 rows — uses `progressions` table from V18)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting progressions...'; END $$;

INSERT INTO progressions (id, program_id, user_id, title, content,
    metrics, metric_labels, is_public, created_at, updated_at)
VALUES
  ('15100000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'Woche 3 — Langer Lauf 18 km',
   'Fantastischer Lauf heute im Tiergarten. Das Tempo hat gestimmt und die Beine fühlen sich nach der Erholung besser an.',
   ARRAY[18.2, 5.42, 148.0]::float[], ARRAY['km', 'min/km', 'bpm'], TRUE,
   NOW()-INTERVAL '35 days', NOW()-INTERVAL '35 days'),

  ('15100000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
   'Erste Yogastunde — Grundlagen',
   'Heute haben wir Surya Namaskar und die wichtigsten stehenden Asanas geübt. Die Gruppe war sehr motiviert!',
   ARRAY[60.0, 12.0]::float[], ARRAY['minuten', 'asanas'], TRUE,
   NOW()-INTERVAL '30 days', NOW()-INTERVAL '30 days'),

  ('15100000-0000-0000-0000-000000000003',
   '40000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003',
   'Tempoeinheit — 5×1 km',
   'Heute 5 Kilometer-Intervalle am Alsterufer. Beste Zeit: 4:18 min/km. Sehr zufrieden!',
   ARRAY[8.0, 4.18, 162.0]::float[], ARRAY['km', 'min/km', 'bpm'], TRUE,
   NOW()-INTERVAL '25 days', NOW()-INTERVAL '25 days'),

  ('15100000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000004',
   'Bouldern — Neues Projekt 6A',
   'Endlich den 6A-Überhang geflasht! Hat zwei Wochen gedauert aber die Beintechnik hat den Unterschied gemacht.',
   ARRAY[120.0, 6.0, 3.0]::float[], ARRAY['minuten', 'grade', 'versuche'], TRUE,
   NOW()-INTERVAL '22 days', NOW()-INTERVAL '22 days'),

  ('15100000-0000-0000-0000-000000000005',
   '40000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000005',
   'Bootcamp Woche 2 — HIIT Circuit',
   'Drei Runden Circuit-Training: Burpees, Box Jumps, Kettlebell Swings. Herzfrequenz konstant über 170 bpm.',
   ARRAY[45.0, 174.0, 320.0]::float[], ARRAY['minuten', 'bpm', 'kalorien'], FALSE,
   NOW()-INTERVAL '20 days', NOW()-INTERVAL '20 days'),

  ('15100000-0000-0000-0000-000000000006',
   '40000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000006',
   'Schwimmtraining — 2.000 m Kraul',
   '2.000 m in 42:15 geschwommen. Wendezeiten verbessert, Armeinsatz überarbeitet.',
   ARRAY[2000.0, 42.25, 1.27]::float[], ARRAY['meter', 'minuten', 'min/100m'], TRUE,
   NOW()-INTERVAL '18 days', NOW()-INTERVAL '18 days'),

  ('15100000-0000-0000-0000-000000000007',
   '40000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000007',
   'Kickboxen — Erste Kombination',
   'Heute die Jab-Cross-Hook-Kombination gelernt und geübt. Standbein und Hüftrotation müssen noch verbessert werden.',
   ARRAY[60.0, 150.0, 3.0]::float[], ARRAY['minuten', 'bpm_max', 'kombinationen'], TRUE,
   NOW()-INTERVAL '15 days', NOW()-INTERVAL '15 days'),

  ('15100000-0000-0000-0000-000000000008',
   '40000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000008',
   'Salsa — Grundschritt sitzt!',
   'Nach drei Stunden sitzt der Grundschritt auf beiden Seiten. Erste einfache Drehfigur geübt.',
   ARRAY[90.0, 4.0]::float[], ARRAY['minuten', 'figuren_gelernt'], TRUE,
   NOW()-INTERVAL '12 days', NOW()-INTERVAL '12 days'),

  ('15100000-0000-0000-0000-000000000009',
   '40000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000009',
   'Gravel-Tour — 52 km fränkische Schweiz',
   'Wunderschöne Route von Ebermannstadt nach Gößweinstein und zurück. 850 Höhenmeter, traumhafter Ausblick!',
   ARRAY[52.3, 850.0, 3.5]::float[], ARRAY['km', 'hm', 'stunden'], TRUE,
   NOW()-INTERVAL '10 days', NOW()-INTERVAL '10 days'),

  ('15100000-0000-0000-0000-000000000010',
   '40000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000010',
   'Pilates — Rückeneinheit',
   'Heute Fokus auf Lendenwirbelsäule und tiefe Bauchmuskulatur. Bereits nach einer Stunde spürbarer Unterschied.',
   ARRAY[60.0, 8.0]::float[], ARRAY['minuten', 'uebungen'], TRUE,
   NOW()-INTERVAL '8 days', NOW()-INTERVAL '8 days');

-- ============================================================
-- 24. AUDIT_LOGS (10 rows)
-- ============================================================
DO $$ BEGIN RAISE NOTICE 'V27 — inserting audit_logs...'; END $$;

INSERT INTO audit_logs (id, user_id, action_type, entity_type, entity_id,
    old_value, new_value, ip_address, user_agent, created_at)
VALUES
  ('16100000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000001', 'LOGIN', 'USER',
   '00000000-0000-0000-0000-000000000001',
   NULL, '{"method":"email","device":"android"}',
   '87.153.24.110', 'Mozilla/5.0 (Android 13; Samsung Galaxy S23)',
   NOW()-INTERVAL '2 hours'),

  ('16100000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-000000000002', 'CREATE', 'PROGRAM',
   '40000000-0000-0000-0000-000000000002',
   NULL, '{"title":"Hatha Yoga für Einsteiger — München","status":"ACTIVE"}',
   '91.65.178.42', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)',
   NOW()-INTERVAL '55 days'),

  ('16100000-0000-0000-0000-000000000003',
   '00000000-0000-0000-0000-000000000003', 'UPDATE', 'USER',
   '00000000-0000-0000-0000-000000000003',
   '{"bio":"Hobbyläufer"}', '{"bio":"Hobbyläufer und Radfahrer aus Hamburg. Halbmarathon unter 2 Stunden ist mein Ziel."}',
   '217.86.193.55', 'Mozilla/5.0 (Linux; Android 13; Pixel 7)',
   NOW()-INTERVAL '45 days'),

  ('16100000-0000-0000-0000-000000000004',
   '00000000-0000-0000-0000-000000000004', 'CREATE', 'REVIEW',
   'B1000000-0000-0000-0000-000000000003',
   NULL, '{"score":4.0,"programId":"40000000-0000-0000-0000-000000000003"}',
   '78.42.11.209', 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6)',
   NOW()-INTERVAL '18 days'),

  ('16100000-0000-0000-0000-000000000005',
   '00000000-0000-0000-0000-000000000005', 'LOGIN', 'USER',
   '00000000-0000-0000-0000-000000000005',
   NULL, '{"method":"email","device":"android"}',
   '5.56.201.177', 'Mozilla/5.0 (Linux; Android 14; OnePlus 12)',
   NOW()-INTERVAL '5 minutes'),

  ('16100000-0000-0000-0000-000000000006',
   '00000000-0000-0000-0000-000000000006', 'UPDATE', 'PROGRAM',
   '40000000-0000-0000-0000-000000000006',
   '{"status":"DRAFT"}', '{"status":"ACTIVE"}',
   '193.111.225.88', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_1)',
   NOW()-INTERVAL '36 days'),

  ('16100000-0000-0000-0000-000000000007',
   '00000000-0000-0000-0000-000000000007', 'CREATE', 'MESSAGE',
   '80000000-0000-0000-0000-000000000006',
   NULL, '{"conversationId":"70000000-0000-0000-0000-000000000006","length":87}',
   '46.114.7.23', 'Mozilla/5.0 (Linux; Android 13; Samsung Galaxy A54)',
   NOW()-INTERVAL '6 days'),

  ('16100000-0000-0000-0000-000000000008',
   '00000000-0000-0000-0000-000000000008', 'LOGOUT', 'USER',
   '00000000-0000-0000-0000-000000000008',
   NULL, '{"device":"iphone13"}',
   '80.228.116.44', 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_4)',
   NOW()-INTERVAL '6 hours'),

  ('16100000-0000-0000-0000-000000000009',
   '00000000-0000-0000-0000-000000000009', 'CREATE', 'PROGRESSION',
   '15100000-0000-0000-0000-000000000009',
   NULL, '{"title":"Gravel-Tour — 52 km fränkische Schweiz"}',
   '62.159.84.201', 'Mozilla/5.0 (Linux; Android 14; Xiaomi 13)',
   NOW()-INTERVAL '10 days'),

  ('16100000-0000-0000-0000-000000000010',
   '00000000-0000-0000-0000-000000000010', 'EXPORT', 'USER',
   '00000000-0000-0000-0000-000000000010',
   NULL, '{"format":"JSON","tables":["users","user_activities","programs"]}',
   '212.67.98.15', 'Mozilla/5.0 (Windows NT 10.0; Chrome/119.0)',
   NOW()-INTERVAL '5 days');

-- ============================================================
-- Final notice
-- ============================================================
DO $$ BEGIN
    RAISE NOTICE 'V27 — seed complete. 10 rows in each of 24 tables (Germany data).';
    RAISE NOTICE 'V27 — user seyd.njoya@icloud.com preserved as id 00000000-0000-0000-0000-000000000001.';
END $$;
