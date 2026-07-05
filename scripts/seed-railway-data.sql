-- Script SQL pour charger des données de test dans Railway (Version simplifiée)
-- 10 occurrences par table avec des coordonnées allemandes
-- Usage: psql $DATABASE_URL < seed-railway-data-v2.sql

-- Mot de passe: Railway1234!
-- Hash bcrypt: $2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa

-- ============================================
-- 1. UTILISATEURS (10 users dans 10 villes)
-- ============================================

INSERT INTO users (id, email, password_hash, display_name, bio, location, blur_radius_m, location_public, online_status_visible, receive_messages, verification_status, verified_at, is_active, last_active_at, created_at)
VALUES
-- Railway User 1 - Berlin
(gen_random_uuid(), 'railway1@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Max Müller', 'Sportbegeistert und immer auf der Suche nach neuen Trainingspartnern.',
 ST_SetSRID(ST_MakePoint(13.4050, 52.5200), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 2 - München
(gen_random_uuid(), 'railway2@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Anna Schmidt', 'Kunstliebhaberin, die gerne kreative Workshops besucht.',
 ST_SetSRID(ST_MakePoint(11.5820, 48.1351), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 3 - Hamburg
(gen_random_uuid(), 'railway3@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Lukas Wagner', 'Technik-Enthusiast und Hobby-Programmierer.',
 ST_SetSRID(ST_MakePoint(9.9937, 53.5511), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 4 - Köln
(gen_random_uuid(), 'railway4@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Sophie Fischer', 'Naturliebhaberin, die gerne wandert und klettert.',
 ST_SetSRID(ST_MakePoint(6.9603, 50.9375), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 5 - Frankfurt
(gen_random_uuid(), 'railway5@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Felix Weber', 'Musikfan, spielt Gitarre und geht gerne auf Konzerte.',
 ST_SetSRID(ST_MakePoint(8.6821, 50.1109), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 6 - Stuttgart
(gen_random_uuid(), 'railway6@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Emma Meyer', 'Yogalehrerin und Meditationsfan.',
 ST_SetSRID(ST_MakePoint(9.1829, 48.7758), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 7 - Düsseldorf
(gen_random_uuid(), 'railway7@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Jonas Becker', 'Fußballspieler auf der Suche nach einer Mannschaft.',
 ST_SetSRID(ST_MakePoint(6.7735, 51.2277), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 8 - Dortmund
(gen_random_uuid(), 'railway8@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Mia Schulz', 'Buchliebhaberin und Mitglied eines Leseclubs.',
 ST_SetSRID(ST_MakePoint(7.4653, 51.5136), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 9 - Leipzig
(gen_random_uuid(), 'railway9@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Leon Hoffmann', 'Fotograf mit Fokus auf Straßenfotografie.',
 ST_SetSRID(ST_MakePoint(12.3731, 51.3397), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW()),

-- Railway User 10 - Dresden
(gen_random_uuid(), 'railway10@pair.app', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Lena Koch', 'Köchin, die gerne neue Rezepte ausprobiert.',
 ST_SetSRID(ST_MakePoint(13.7373, 51.0504), 4326), 500, true, true, true, 'EMAIL_VERIFIED', NOW(), true, NOW(), NOW());


-- ============================================
-- 2. USER_ACTIVITIES (10 activités)
-- ============================================

-- User 1 - Yoga
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Hatha Yoga für Anfänger und Fortgeschrittene', 'BEGINNER', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway1@pair.app' AND a.slug = 'yoga';

-- User 2 - Course à pied
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Lauftraining für den Marathon', 'INTERMEDIATE', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway2@pair.app' AND a.slug = 'course-a-pied';

-- User 3 - Escalade
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Bouldern in der Halle und draußen', 'ADVANCED', 'DUO', NOW()
FROM users u, activities a
WHERE u.email = 'railway3@pair.app' AND a.slug = 'escalade';

-- User 4 - Football
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Fußball spielen im Team', 'INTERMEDIATE', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway4@pair.app' AND a.slug = 'football';

-- User 5 - Natation
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Kraulschwimmen und Ausdauertraining', 'BEGINNER', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway5@pair.app' AND a.slug = 'natation';

-- User 6 - Tennis
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Tennis Doppel und Einzel', 'INTERMEDIATE', 'DUO', NOW()
FROM users u, activities a
WHERE u.email = 'railway6@pair.app' AND a.slug = 'tennis';

-- User 7 - Programmation
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Web-Entwicklung und Open Source Projekte', 'ADVANCED', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway7@pair.app' AND a.slug = 'programmation';

-- User 8 - Photographie
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Straßenfotografie und Portraitfotografie', 'INTERMEDIATE', 'DUO', NOW()
FROM users u, activities a
WHERE u.email = 'railway8@pair.app' AND a.slug = 'photographie';

-- User 9 - Cuisine
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Internationale Küche gemeinsam kochen', 'BEGINNER', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway9@pair.app' AND a.slug = 'cuisine-du-monde';

-- User 10 - Méditation
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id, true, 'Achtsamkeitsmeditation für Anfänger', 'ANY', 'GROUP', NOW()
FROM users u, activities a
WHERE u.email = 'railway10@pair.app' AND a.slug = 'meditation';


-- ============================================
-- 3. PROGRAMS (10 programmes)
-- ============================================

-- Program 1 - Yoga am Morgen
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Yoga am Morgen',
       'Starte den Tag mit einer entspannenden Yoga-Session. Alle Level willkommen.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway1@pair.app';

-- Program 2 - Marathon Vorbereitung
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Marathon Vorbereitung',
       'Gemeinsames Training für den kommenden Marathon. Wöchentliche Läufe von 15-25km.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway2@pair.app';

-- Program 3 - Kletter-Workshop
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Kletter-Workshop',
       'Lerne die Grundlagen des Kletterns in einer professionellen Halle.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway3@pair.app';

-- Program 4 - Fußball Freundschaftsspiel
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Fußball Freundschaftsspiel',
       'Wöchentliches Freundschaftsspiel für alle Fußball-Fans.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway4@pair.app';

-- Program 5 - Schwimmtraining
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Schwimmtraining',
       'Techniktraining und Ausdauerschwimmen für Fortgeschrittene.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway5@pair.app';

-- Program 6 - Tennis Doppel
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Tennis Doppel',
       'Suche Spielpartner für regelmäßige Tennis-Doppel.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway6@pair.app';

-- Program 7 - Hackathon Wochenende
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Hackathon Wochenende',
       '48 Stunden coden, networken und neue Projekte starten.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway7@pair.app';

-- Program 8 - Fotowalk durch die Stadt
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Fotowalk durch die Stadt',
       'Entdecke die Stadt durch die Linse. Für Anfänger und Profis.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway8@pair.app';

-- Program 9 - Kochkurs Asiatisch
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Kochkurs Asiatisch',
       'Gemeinsam asiatische Gerichte kochen und genießen.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway9@pair.app';

-- Program 10 - Meditation am Abend
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at)
SELECT gen_random_uuid(), ua.id, 'Meditation am Abend',
       'Finde innere Ruhe durch geführte Meditation.',
       'ACTIVE', true, u.display_name, u.avatar_url, NOW()
FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email = 'railway10@pair.app';


-- ============================================
-- 4. SCHEDULES (10 horaires)
-- ============================================

-- Schedule 1 - Yoga Studio Berlin (Lundi 9h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Yoga Studio Berlin', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(13.4100, 52.5250), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '9 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '11 hours')::timestamp,
       12, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway1@pair.app';

-- Schedule 2 - Olympiastadion München (Mardi 8h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Olympiastadion München', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(11.5900, 48.1400), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '1 day' + INTERVAL '8 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '1 day' + INTERVAL '10 hours')::timestamp,
       15, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway2@pair.app';

-- Schedule 3 - Kletterhalle Hamburg (Mercredi 18h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Kletterhalle Hamburg', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(10.0000, 53.5550), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '2 days' + INTERVAL '18 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '2 days' + INTERVAL '20 hours')::timestamp,
       6, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway3@pair.app';

-- Schedule 4 - Sportplatz Köln (Jeudi 20h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Sportplatz Köln', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(6.9650, 50.9400), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '3 days' + INTERVAL '20 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '3 days' + INTERVAL '22 hours')::timestamp,
       22, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway4@pair.app';

-- Schedule 5 - Schwimmbad Frankfurt (Vendredi 19h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Schwimmbad Frankfurt', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(8.6850, 50.1150), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '4 days' + INTERVAL '19 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '4 days' + INTERVAL '21 hours')::timestamp,
       8, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway5@pair.app';

-- Schedule 6 - Tennis Club Stuttgart (Samedi 10h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Tennis Club Stuttgart', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(9.1850, 48.7800), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '5 days' + INTERVAL '10 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '5 days' + INTERVAL '12 hours')::timestamp,
       4, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway6@pair.app';

-- Schedule 7 - Tech Hub Düsseldorf (Dimanche 10h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Tech Hub Düsseldorf', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(6.7750, 51.2300), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '6 days' + INTERVAL '10 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '6 days' + INTERVAL '12 hours')::timestamp,
       30, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway7@pair.app';

-- Schedule 8 - Altstadt Dortmund (Samedi 14h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Altstadt Dortmund', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(7.4700, 51.5150), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '5 days' + INTERVAL '14 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '5 days' + INTERVAL '16 hours')::timestamp,
       10, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway8@pair.app';

-- Schedule 9 - Kochschule Leipzig (Vendredi 19h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Kochschule Leipzig', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(12.3750, 51.3420), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '4 days' + INTERVAL '19 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '4 days' + INTERVAL '21 hours')::timestamp,
       12, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway9@pair.app';

-- Schedule 10 - Wellness Center Dresden (Mercredi 19h)
INSERT INTO schedules (id, program_id, place_name, place_type, location, show_exact_address, starts_at, ends_at, max_participants, created_at)
SELECT gen_random_uuid(), p.id, 'Wellness Center Dresden', 'PUBLIC',
       ST_SetSRID(ST_MakePoint(13.7400, 51.0520), 4326), false,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '2 days' + INTERVAL '19 hours')::timestamp,
       (CURRENT_DATE + INTERVAL '1 week' + INTERVAL '2 days' + INTERVAL '21 hours')::timestamp,
       15, NOW()
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'railway10@pair.app';


-- ============================================
-- 5. USER_PROGRAMS (10 inscriptions)
-- ============================================

-- Chaque utilisateur s'inscrit au programme suivant
INSERT INTO user_programs (id, program_id, user_id, status, joined_at)
SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '1 day'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway1@pair.app' AND u2.email = 'railway2@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '2 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway2@pair.app' AND u2.email = 'railway3@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '3 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway3@pair.app' AND u2.email = 'railway4@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '4 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway4@pair.app' AND u2.email = 'railway5@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '5 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway5@pair.app' AND u2.email = 'railway6@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '6 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway6@pair.app' AND u2.email = 'railway7@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '7 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway7@pair.app' AND u2.email = 'railway8@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '8 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway8@pair.app' AND u2.email = 'railway9@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '9 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway9@pair.app' AND u2.email = 'railway10@pair.app'

UNION ALL

SELECT gen_random_uuid(), p.id, u2.id, 'ACTIVE', NOW() - INTERVAL '10 days'
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u1 ON ua.user_id = u1.id
CROSS JOIN users u2
WHERE u1.email = 'railway10@pair.app' AND u2.email = 'railway1@pair.app';


-- ============================================
-- FIN DU SCRIPT
-- ============================================

-- Vérification
SELECT 'Utilisateurs créés:' as info, COUNT(*) as count FROM users WHERE email LIKE 'railway%@pair.app'
UNION ALL
SELECT 'Activités utilisateur:', COUNT(*) FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%@pair.app'
UNION ALL
SELECT 'Programmes créés:', COUNT(*) FROM programs p JOIN user_activities ua ON p.user_activity_id = ua.id JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%@pair.app'
UNION ALL
SELECT 'Horaires créés:', COUNT(*) FROM schedules s JOIN programs p ON s.program_id = p.id JOIN user_activities ua ON p.user_activity_id = ua.id JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%@pair.app'
UNION ALL
SELECT 'Inscriptions:', COUNT(*) FROM user_programs;
