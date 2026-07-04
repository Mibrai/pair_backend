-- =============================================================================
-- seed-map-data.sql
-- Peuple la BD avec des utilisateurs, programmes et schedules géolocalisés
-- en Allemagne pour que /api/map/activities affiche des marqueurs sur la carte.
--
-- Chaîne requise : schedules → programs → user_activities → activities
--
-- Idempotent : INSERT ... ON CONFLICT DO NOTHING partout.
-- UUIDs fixes pour éviter les doublons à chaque exécution.
-- Mot de passe commun des faux utilisateurs : Test1234!
-- =============================================================================

BEGIN;

-- =============================================================================
-- 1. UTILISATEURS FICTIFS — 6 villes allemandes
--    Hash BCrypt de Test1234! :
--    $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y
-- =============================================================================
INSERT INTO users (
    id, email, password_hash, display_name, bio,
    location, blur_radius_m, location_public, online_status_visible,
    receive_messages, verification_status, verified_at,
    created_at, last_active_at, is_active
) VALUES
-- Berlin  (52.5200 N, 13.4050 E)
('a1de0000-0000-0000-0000-000000000001',
 'lena.mueller@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Lena Müller', 'Joggeuse passionnée à Berlin, marathons réguliers.',
 ST_SetSRID(ST_MakePoint(13.4050, 52.5200), 4326),
 200, TRUE, TRUE, TRUE, 'VERIFIED', NOW()-INTERVAL '60 days',
 NOW()-INTERVAL '60 days', NOW()-INTERVAL '1 hour', TRUE),

-- München (48.1351 N, 11.5820 E)
('a1de0000-0000-0000-0000-000000000002',
 'max.bauer@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Max Bauer', 'Footballeur amateur à München, sorties weekend en équipe.',
 ST_SetSRID(ST_MakePoint(11.5820, 48.1351), 4326),
 300, TRUE, TRUE, TRUE, 'VERIFIED', NOW()-INTERVAL '45 days',
 NOW()-INTERVAL '45 days', NOW()-INTERVAL '2 hours', TRUE),

-- Hamburg (53.5753 N, 10.0153 E)
('a1de0000-0000-0000-0000-000000000003',
 'sophie.schmidt@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Sophie Schmidt', 'Instructrice yoga certifiée à Hamburg, cours en plein air.',
 ST_SetSRID(ST_MakePoint(10.0153, 53.5753), 4326),
 400, TRUE, FALSE, TRUE, 'VERIFIED', NOW()-INTERVAL '90 days',
 NOW()-INTERVAL '90 days', NOW()-INTERVAL '30 minutes', TRUE),

-- Köln   (50.9333 N,  6.9500 E)
('a1de0000-0000-0000-0000-000000000004',
 'felix.wagner@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Felix Wagner', 'Cycliste confirmé à Köln, routes et pistes cyclables du Rhin.',
 ST_SetSRID(ST_MakePoint(6.9500, 50.9333), 4326),
 500, TRUE, TRUE, FALSE, 'UNVERIFIED', NULL,
 NOW()-INTERVAL '30 days', NOW()-INTERVAL '3 days', TRUE),

-- Frankfurt (50.1109 N, 8.6821 E)
('a1de0000-0000-0000-0000-000000000005',
 'anna.klein@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Anna Klein', 'Nageuse niveau régional à Frankfurt, entraînements biquotidiens.',
 ST_SetSRID(ST_MakePoint(8.6821, 50.1109), 4326),
 200, TRUE, TRUE, TRUE, 'VERIFIED', NOW()-INTERVAL '50 days',
 NOW()-INTERVAL '50 days', NOW()-INTERVAL '4 hours', TRUE),

-- Stuttgart (48.7758 N, 9.1829 E)
('a1de0000-0000-0000-0000-000000000006',
 'thomas.richter@pair-test.de',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 'Thomas Richter', 'Randonneur passionné autour de Stuttgart et de la Forêt Noire.',
 ST_SetSRID(ST_MakePoint(9.1829, 48.7758), 4326),
 600, TRUE, FALSE, TRUE, 'VERIFIED', NOW()-INTERVAL '120 days',
 NOW()-INTERVAL '120 days', NOW()-INTERVAL '6 hours', TRUE)

ON CONFLICT (email) DO NOTHING;

-- Mettre à jour la localisation de seyd.njoya@icloud.com → Berlin
-- (nécessaire pour que la carte le positionne en Allemagne)
UPDATE users
SET location            = ST_SetSRID(ST_MakePoint(13.3888, 52.5170), 4326),
    location_public     = TRUE,
    blur_radius_m       = 200,
    last_active_at      = NOW()
WHERE email = 'seyd.njoya@icloud.com';

-- =============================================================================
-- 2. USER_ACTIVITIES — lier chaque utilisateur à une activité du catalogue
--    On utilise les slugs pour être robuste face aux UUIDs variables en BD.
-- =============================================================================

-- lena → running
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, 'Coach running certifiée Berlin, tous niveaux', 'ADVANCED', 'GROUP',
       NOW()-INTERVAL '55 days'
FROM users u JOIN activities a ON a.slug = 'running'
WHERE u.email = 'lena.mueller@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- max → football
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, 'Milieu de terrain, cherche équipe weekend', 'INTERMEDIATE', 'GROUP',
       NOW()-INTERVAL '40 days'
FROM users u JOIN activities a ON a.slug = 'football'
WHERE u.email = 'max.bauer@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- sophie → yoga
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, 'Cours yoga Hatha en plein air, petits groupes max 10', 'ADVANCED', 'GROUP',
       NOW()-INTERVAL '85 days'
FROM users u JOIN activities a ON a.slug = 'yoga'
WHERE u.email = 'sophie.schmidt@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- felix → cyclisme
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, 'Sorties vélo route et piste le long du Rhin', 'INTERMEDIATE', 'GROUP',
       NOW()-INTERVAL '25 days'
FROM users u JOIN activities a ON a.slug = 'cyclisme'
WHERE u.email = 'felix.wagner@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- anna → natation
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, 'Entraînements natation endurance et technique', 'ADVANCED', 'GROUP',
       NOW()-INTERVAL '45 days'
FROM users u JOIN activities a ON a.slug = 'natation'
WHERE u.email = 'anna.klein@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- thomas → randonnee
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, NULL, 'BEGINNER', 'GROUP',
       NOW()-INTERVAL '110 days'
FROM users u JOIN activities a ON a.slug = 'randonnee'
WHERE u.email = 'thomas.richter@pair-test.de'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- seyd → running
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, NULL, 'BEGINNER', 'GROUP', NOW()-INTERVAL '10 days'
FROM users u JOIN activities a ON a.slug = 'running'
WHERE u.email = 'seyd.njoya@icloud.com'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- seyd → football
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, NULL, 'INTERMEDIATE', 'GROUP', NOW()-INTERVAL '10 days'
FROM users u JOIN activities a ON a.slug = 'football'
WHERE u.email = 'seyd.njoya@icloud.com'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- seyd → yoga
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at)
SELECT gen_random_uuid(), u.id, a.id,
       TRUE, NULL, 'BEGINNER', 'SOLO', NOW()-INTERVAL '10 days'
FROM users u JOIN activities a ON a.slug = 'yoga'
WHERE u.email = 'seyd.njoya@icloud.com'
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- =============================================================================
-- 3. PROGRAMMES — un programme par user_activity (UUIDs fixes = idempotent)
-- =============================================================================

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000001', ua.id,
       'Running Berlin Débutants',
       'Programme 8 semaines pour débuter le running à Berlin. Tiergarten, sam 7h30.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '50 days', NOW()-INTERVAL '5 days'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'lena.mueller@pair-test.de' AND a.slug = 'running'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000002', ua.id,
       'Football 5x5 München',
       'Matches amicaux 5c5 chaque samedi. Terrain Olympiapark. Tous niveaux bienvenus.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '35 days', NOW()-INTERVAL '3 days'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'max.bauer@pair-test.de' AND a.slug = 'football'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000003', ua.id,
       'Yoga Matin Stadtpark Hamburg',
       'Séance yoga Hatha 60 min en plein air. Stadtpark Hamburg, lun/mer/ven 7h.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '75 days', NOW()-INTERVAL '1 day'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'sophie.schmidt@pair-test.de' AND a.slug = 'yoga'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000004', ua.id,
       'Sortie Vélo Rheinpark Köln',
       'Sortie cyclisme dimanche matin, 40 km le long du Rhin. Tous niveaux bienvenus.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '20 days', NOW()-INTERVAL '2 days'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'felix.wagner@pair-test.de' AND a.slug = 'cyclisme'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000005', ua.id,
       'Natation Endurance Frankfurt',
       'Entraînement natation endurance, 2500 m/session. Brentanobad, couloir réservé.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '40 days', NOW()-INTERVAL '4 days'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'anna.klein@pair-test.de' AND a.slug = 'natation'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000006', ua.id,
       'Randonnée Forêt Noire — Stuttgart',
       'Sorties randonnée en Forêt Noire, 10-20 km. Départ Stuttgart, tous niveaux.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '100 days', NOW()-INTERVAL '7 days'
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'thomas.richter@pair-test.de' AND a.slug = 'randonnee'
ON CONFLICT (id) DO NOTHING;

-- Programmes de seyd.njoya@icloud.com
INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000007', ua.id,
       'Circuit Running Matinal — Berlin',
       'Sorties running en groupe, 5 à 10 km. Départ Mauerpark, mer et sam 6h30.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '8 days', NULL
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'seyd.njoya@icloud.com' AND a.slug = 'running'
ON CONFLICT (id) DO NOTHING;

INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at)
SELECT 'b1de0000-0000-0000-0000-000000000008', ua.id,
       'Football Loisir Samedi — Berlin',
       'Matches de football amicaux chaque samedi matin. Format 7v7, terrain gazon.',
       NULL, 'ACTIVE', TRUE, NULL, NOW()-INTERVAL '8 days', NULL
FROM user_activities ua
JOIN users u     ON u.id  = ua.user_id
JOIN activities a ON a.id = ua.activity_id
WHERE u.email = 'seyd.njoya@icloud.com' AND a.slug = 'football'
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 4. SCHEDULES — 2 créneaux par programme, coordonnées allemandes réelles
-- =============================================================================

-- ── Programme 1 : Running Berlin ─────────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000001',
 'b1de0000-0000-0000-0000-000000000001',
 'Tiergarten Berlin', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.3503, 52.5145), 4326),
 'Straße des 17. Juni, 10785 Berlin', TRUE,
 NOW()+INTERVAL '7 days',
 NOW()+INTERVAL '7 days' +INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=SA', 15, NOW()-INTERVAL '49 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000002',
 'b1de0000-0000-0000-0000-000000000001',
 'Volkspark Friedrichshain', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.4336, 52.5274), 4326),
 'Am Volkspark, 10249 Berlin', TRUE,
 NOW()+INTERVAL '14 days',
 NOW()+INTERVAL '14 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=SA', 15, NOW()-INTERVAL '48 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 2 : Football München ───────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000003',
 'b1de0000-0000-0000-0000-000000000002',
 'Olympiapark München', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(11.5516, 48.1748), 4326),
 'Spiridon-Louis-Ring 21, 80809 München', TRUE,
 NOW()+INTERVAL '5 days',
 NOW()+INTERVAL '5 days' +INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA', 14, NOW()-INTERVAL '34 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000004',
 'b1de0000-0000-0000-0000-000000000002',
 'Englischer Garten München', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(11.5950, 48.1642), 4326),
 'Englischer Garten 1, 80538 München', TRUE,
 NOW()+INTERVAL '12 days',
 NOW()+INTERVAL '12 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA', 14, NOW()-INTERVAL '33 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 3 : Yoga Hamburg ────────────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000005',
 'b1de0000-0000-0000-0000-000000000003',
 'Stadtpark Hamburg', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(10.0182, 53.5930), 4326),
 'Am Stadtpark 1, 22299 Hamburg', TRUE,
 NOW()+INTERVAL '1 day',
 NOW()+INTERVAL '1 day' +INTERVAL '60 minutes',
 'FREQ=WEEKLY;BYDAY=MO,WE,FR', 10, NOW()-INTERVAL '74 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000006',
 'b1de0000-0000-0000-0000-000000000003',
 'Alsterpark Hamburg', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(10.0047, 53.5795), 4326),
 'Alsterchaussee, 20149 Hamburg', TRUE,
 NOW()+INTERVAL '3 days',
 NOW()+INTERVAL '3 days' +INTERVAL '60 minutes',
 'FREQ=WEEKLY;BYDAY=MO,WE,FR', 10, NOW()-INTERVAL '73 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 4 : Cyclisme Köln ───────────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000007',
 'b1de0000-0000-0000-0000-000000000004',
 'Rheinpark Köln', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(6.9795, 50.9624), 4326),
 'Rheinparkweg 1, 51063 Köln', TRUE,
 NOW()+INTERVAL '6 days',
 NOW()+INTERVAL '6 days' +INTERVAL '3 hours',
 'FREQ=WEEKLY;BYDAY=SU', 20, NOW()-INTERVAL '19 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000008',
 'b1de0000-0000-0000-0000-000000000004',
 'Fühlinger See Köln', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(6.9283, 51.0182), 4326),
 'Fühlingen, 50769 Köln', TRUE,
 NOW()+INTERVAL '13 days',
 NOW()+INTERVAL '13 days'+INTERVAL '3 hours',
 'FREQ=WEEKLY;BYDAY=SU', 20, NOW()-INTERVAL '18 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 5 : Natation Frankfurt ─────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000009',
 'b1de0000-0000-0000-0000-000000000005',
 'Brentanobad Frankfurt', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(8.6119, 50.1234), 4326),
 'Rödelheimer Parkweg 12, 60489 Frankfurt am Main', TRUE,
 NOW()+INTERVAL '2 days',
 NOW()+INTERVAL '2 days' +INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=TU,TH', 8, NOW()-INTERVAL '39 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000010',
 'b1de0000-0000-0000-0000-000000000005',
 'Rebstockbad Frankfurt', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(8.6285, 50.1067), 4326),
 'August-Euler-Straße 7, 60486 Frankfurt am Main', TRUE,
 NOW()+INTERVAL '9 days',
 NOW()+INTERVAL '9 days' +INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=TU,TH', 8, NOW()-INTERVAL '38 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 6 : Randonnée Stuttgart ────────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000011',
 'b1de0000-0000-0000-0000-000000000006',
 'Schlossgarten Stuttgart', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(9.1807, 48.7880), 4326),
 'Schillerplatz, 70173 Stuttgart', TRUE,
 NOW()+INTERVAL '4 days',
 NOW()+INTERVAL '4 days' +INTERVAL '4 hours',
 'FREQ=WEEKLY;BYDAY=SA', 25, NOW()-INTERVAL '99 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000012',
 'b1de0000-0000-0000-0000-000000000006',
 'Solitudepark Stuttgart', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(9.0963, 48.8018), 4326),
 'Solitude 1, 71638 Ludwigsburg', TRUE,
 NOW()+INTERVAL '11 days',
 NOW()+INTERVAL '11 days'+INTERVAL '4 hours',
 'FREQ=WEEKLY;BYDAY=SA', 25, NOW()-INTERVAL '98 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 7 : Running seyd — Berlin ──────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000013',
 'b1de0000-0000-0000-0000-000000000007',
 'Mauerpark Berlin', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.4022, 52.5417), 4326),
 'Bernauer Str. 63, 13355 Berlin', TRUE,
 NOW()+INTERVAL '3 days',
 NOW()+INTERVAL '3 days' +INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=WE,SA', 20, NOW()-INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000014',
 'b1de0000-0000-0000-0000-000000000007',
 'Treptower Park Berlin', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.4678, 52.4883), 4326),
 'Alt-Treptow 1, 12435 Berlin', TRUE,
 NOW()+INTERVAL '10 days',
 NOW()+INTERVAL '10 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=WE,SA', 20, NOW()-INTERVAL '6 days')
ON CONFLICT (id) DO NOTHING;

-- ── Programme 8 : Football seyd — Berlin ─────────────────────────────────────
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000015',
 'b1de0000-0000-0000-0000-000000000008',
 'Hasenheide Berlin', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.4254, 52.4851), 4326),
 'Hasenheide, 10967 Berlin', TRUE,
 NOW()+INTERVAL '8 days',
 NOW()+INTERVAL '8 days' +INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA', 14, NOW()-INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
VALUES
('c1de0000-0000-0000-0000-000000000016',
 'b1de0000-0000-0000-0000-000000000008',
 'Tempelhof Feld Berlin', 'PUBLIC',
 ST_SetSRID(ST_MakePoint(13.4058, 52.4742), 4326),
 'Tempelhofer Damm, 12101 Berlin', TRUE,
 NOW()+INTERVAL '15 days',
 NOW()+INTERVAL '15 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA', 14, NOW()-INTERVAL '6 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- Vérification finale
-- =============================================================================
SELECT
    a.name                          AS activite,
    p.title                         AS programme,
    s.place_name                    AS lieu,
    round(ST_Y(s.location)::numeric, 4) AS lat,
    round(ST_X(s.location)::numeric, 4) AS lng,
    u.email                         AS organisateur
FROM schedules s
JOIN programs      p  ON p.id  = s.program_id
JOIN user_activities ua ON ua.id = p.user_activity_id
JOIN activities    a  ON a.id  = ua.activity_id
JOIN users         u  ON u.id  = ua.user_id
WHERE s.id::text LIKE 'c1de0000%'
ORDER BY a.name, p.title, s.place_name;

COMMIT;
