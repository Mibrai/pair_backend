-- Script pour charger Railway avec 10 occurrences par table principale
-- Respecte toutes les contraintes de clés étrangères et dépendances

-- Mot de passe pour tous les users: Test1234!
-- Hash BCrypt: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y

-- ============================================================
-- 1. USERS (10)
-- ============================================================
INSERT INTO users (id, email, password_hash, phone, display_name, bio,
                   location, blur_radius_m, location_public, online_status_visible,
                   receive_messages, verification_status, verified_at,
                   created_at, last_active_at, is_active) VALUES
('aaaaaaaa-0000-0000-0000-000000000001','alice@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33601020304','Alice Dupont','Coach fitness certifiée, passionnée de running et triathlon.',
 ST_SetSRID(ST_MakePoint(2.3488,48.8534),4326),500,TRUE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '90 days',NOW()-INTERVAL '90 days',NOW()-INTERVAL '2 hours',TRUE),

('aaaaaaaa-0000-0000-0000-000000000002','bob@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33602030405','Bob Martin','Footballeur amateur depuis 10 ans. Cherche partenaires de sport.',
 ST_SetSRID(ST_MakePoint(2.3200,48.8700),4326),300,TRUE,FALSE,TRUE,
 'VERIFIED',NOW()-INTERVAL '60 days',NOW()-INTERVAL '60 days',NOW()-INTERVAL '1 day',TRUE),

('aaaaaaaa-0000-0000-0000-000000000003','claire@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33603040506','Claire Lebrun','Instructrice yoga Hatha 8 ans d''expérience et méditation pleine conscience.',
 ST_SetSRID(ST_MakePoint(2.3600,48.8600),4326),700,FALSE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '120 days',NOW()-INTERVAL '120 days',NOW()-INTERVAL '30 minutes',TRUE),

('aaaaaaaa-0000-0000-0000-000000000004','david@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33604050607','David Moreau','Cycliste passionné route et VTT. Sorties régulières Bois de Boulogne.',
 ST_SetSRID(ST_MakePoint(2.2400,48.8650),4326),500,TRUE,TRUE,FALSE,
 'UNVERIFIED',NULL,NOW()-INTERVAL '30 days',NOW()-INTERVAL '3 days',TRUE),

('aaaaaaaa-0000-0000-0000-000000000005','emma@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33605060708','Emma Wilson','Nageuse niveau régional, piscine Molitor. Triathlète en devenir.',
 ST_SetSRID(ST_MakePoint(2.2700,48.8500),4326),400,TRUE,FALSE,TRUE,
 'VERIFIED',NOW()-INTERVAL '45 days',NOW()-INTERVAL '45 days',NOW()-INTERVAL '5 hours',TRUE),

('aaaaaaaa-0000-0000-0000-000000000006','frank@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33606070809','Frank Dubois','Professeur de karaté ceinture noire 3ème dan. Cours enfants et adultes.',
 ST_SetSRID(ST_MakePoint(2.3400,48.8700),4326),600,TRUE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '150 days',NOW()-INTERVAL '150 days',NOW()-INTERVAL '1 hour',TRUE),

('aaaaaaaa-0000-0000-0000-000000000007','grace@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33607080910','Grace Lambert','Randonneuse expérimentée. Organise sorties montagne et forêt.',
 ST_SetSRID(ST_MakePoint(2.3100,48.8550),4326),500,TRUE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '75 days',NOW()-INTERVAL '75 days',NOW()-INTERVAL '4 hours',TRUE),

('aaaaaaaa-0000-0000-0000-000000000008','hugo@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33608091011','Hugo Bernard','Basketteur passionné. Cherche équipe pour matchs amicaux.',
 ST_SetSRID(ST_MakePoint(2.3500,48.8450),4326),400,TRUE,FALSE,TRUE,
 'VERIFIED',NOW()-INTERVAL '50 days',NOW()-INTERVAL '50 days',NOW()-INTERVAL '8 hours',TRUE),

('aaaaaaaa-0000-0000-0000-000000000009','isabelle@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33609101112','Isabelle Petit','Coach de méditation et pleine conscience. Séances individuelles et groupe.',
 ST_SetSRID(ST_MakePoint(2.3300,48.8580),4326),700,TRUE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '100 days',NOW()-INTERVAL '100 days',NOW()-INTERVAL '15 minutes',TRUE),

('aaaaaaaa-0000-0000-0000-000000000010','julien@pair.test',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh9y',
 '+33610111213','Julien Roux','Fan de judo. Cherche partenaires pour entraînements et compétitions.',
 ST_SetSRID(ST_MakePoint(2.3700,48.8620),4326),500,TRUE,TRUE,TRUE,
 'VERIFIED',NOW()-INTERVAL '40 days',NOW()-INTERVAL '40 days',NOW()-INTERVAL '6 hours',TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 2. CATEGORIES (10)
-- ============================================================
INSERT INTO categories (id, name, icon, color_ramp) VALUES
('bbbbbbbb-0000-0000-0000-000000000001','Sports collectifs','team',    'blue-indigo'),
('bbbbbbbb-0000-0000-0000-000000000002','Sports individuels','person', 'green-teal'),
('bbbbbbbb-0000-0000-0000-000000000003','Bien-être',        'heart',   'purple-pink'),
('bbbbbbbb-0000-0000-0000-000000000004','Arts martiaux',    'shield',  'red-orange'),
('bbbbbbbb-0000-0000-0000-000000000005','Plein air',        'mountain','orange-yellow'),
('bbbbbbbb-0000-0000-0000-000000000006','Aquatique',        'waves',   'cyan-blue'),
('bbbbbbbb-0000-0000-0000-000000000007','Fitness',          'dumbbell','pink-red'),
('bbbbbbbb-0000-0000-0000-000000000008','Danse',            'music',   'violet-purple'),
('bbbbbbbb-0000-0000-0000-000000000009','Sports de raquette','racket', 'lime-green'),
('bbbbbbbb-0000-0000-0000-000000000010','Sports extrêmes',  'zap',     'yellow-orange')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 3. ACTIVITIES (10)
-- ============================================================
INSERT INTO activities (id, parent_id, category_id, name, slug, description, embedding, created_at) VALUES
('cccccccc-0000-0000-0000-000000000001',NULL,'bbbbbbbb-0000-0000-0000-000000000001','Football',   'football',   'Sport collectif le plus populaire au monde.',         NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000002',NULL,'bbbbbbbb-0000-0000-0000-000000000001','Basketball', 'basketball', 'Sport de ballon en équipe de 5 joueurs.',             NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000003',NULL,'bbbbbbbb-0000-0000-0000-000000000002','Natation',   'natation',   'Sport aquatique complet pour tous les âges.',         NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000004',NULL,'bbbbbbbb-0000-0000-0000-000000000002','Cyclisme',   'cyclisme',   'Sport de vélo sur route ou en montagne.',             NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000005',NULL,'bbbbbbbb-0000-0000-0000-000000000002','Running',    'running',    'Course à pied accessible à tous les niveaux.',        NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000006',NULL,'bbbbbbbb-0000-0000-0000-000000000003','Yoga',       'yoga',       'Pratique corps-esprit aux multiples bienfaits.',      NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000007',NULL,'bbbbbbbb-0000-0000-0000-000000000003','Méditation', 'meditation', 'Technique de concentration et pleine conscience.',    NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000008',NULL,'bbbbbbbb-0000-0000-0000-000000000004','Judo',       'judo',       'Art martial japonais de projection au sol.',          NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000009',NULL,'bbbbbbbb-0000-0000-0000-000000000004','Karaté',     'karate',     'Art martial de frappe originaire d''Okinawa.',        NULL,NOW()-INTERVAL '180 days'),
('cccccccc-0000-0000-0000-000000000010',NULL,'bbbbbbbb-0000-0000-0000-000000000005','Randonnée',  'randonnee',  'Marche en nature sur sentiers balisés.',              NULL,NOW()-INTERVAL '180 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 4. USER_ACTIVITIES (10)
-- ============================================================
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at) VALUES
('dddddddd-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000005',TRUE, 'Coach running certifiée, tous niveaux','ADVANCED',    'GROUP',NOW()-INTERVAL '80 days'),
('dddddddd-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000002','cccccccc-0000-0000-0000-000000000001',TRUE, 'Milieu de terrain, cherche équipe WE', 'INTERMEDIATE','GROUP',NOW()-INTERVAL '55 days'),
('dddddddd-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000003','cccccccc-0000-0000-0000-000000000006',TRUE, 'Cours particuliers et petits groupes', 'ADVANCED',    'GROUP',NOW()-INTERVAL '115 days'),
('dddddddd-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000004','cccccccc-0000-0000-0000-000000000004',TRUE, 'Sorties route & VTT niveau interméd+', 'INTERMEDIATE','GROUP',NOW()-INTERVAL '28 days'),
('dddddddd-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000005','cccccccc-0000-0000-0000-000000000003',TRUE, 'Entraînements endurance et technique', 'ADVANCED',    'GROUP',NOW()-INTERVAL '42 days'),
('dddddddd-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000006','cccccccc-0000-0000-0000-000000000009',TRUE, 'Cours karaté tous niveaux',            'ADVANCED',    'GROUP',NOW()-INTERVAL '145 days'),
('dddddddd-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000007','cccccccc-0000-0000-0000-000000000010',TRUE, 'Randonnées weekend et vacances',       'INTERMEDIATE','GROUP',NOW()-INTERVAL '72 days'),
('dddddddd-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000008','cccccccc-0000-0000-0000-000000000002',TRUE, 'Matchs amicaux 3x3 et 5x5',           'INTERMEDIATE','GROUP',NOW()-INTERVAL '48 days'),
('dddddddd-0000-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000009','cccccccc-0000-0000-0000-000000000007',TRUE, 'Séances méditation guidée',            'ADVANCED',    'GROUP',NOW()-INTERVAL '95 days'),
('dddddddd-0000-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000010','cccccccc-0000-0000-0000-000000000008',TRUE, 'Entraînements judo compétition',       'INTERMEDIATE','DUO',  NOW()-INTERVAL '38 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 5. PROGRAMS (10)
-- ============================================================
INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at) VALUES
('eeeeeeee-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-000000000001',
 'Running Paris Débutants','Programme 8 semaines pour débuter le running en toute sécurité.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '75 days',NOW()-INTERVAL '10 days'),

('eeeeeeee-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-000000000002',
 'Football 5x5 Samedi','Match amical 5c5 chaque samedi. Terrain synthétique.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '50 days',NOW()-INTERVAL '5 days'),

('eeeeeeee-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-000000000003',
 'Yoga Hatha Matin','Séance yoga Hatha 75 min en petit groupe (max 8).',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '110 days',NOW()-INTERVAL '2 days'),

('eeeeeeee-0000-0000-0000-000000000004','dddddddd-0000-0000-0000-000000000004',
 'Sortie Vélo Bois','Sortie cyclisme dimanche matin, 30 km à rythme libre.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '25 days',NOW()-INTERVAL '1 day'),

('eeeeeeee-0000-0000-0000-000000000005','dddddddd-0000-0000-0000-000000000005',
 'Natation Endurance','Entraînement natation endurance, 2000 m/session.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '40 days',NOW()-INTERVAL '3 days'),

('eeeeeeee-0000-0000-0000-000000000006','dddddddd-0000-0000-0000-000000000006',
 'Karaté Adultes','Cours de karaté pour adultes tous niveaux. 2x/semaine.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '140 days',NOW()-INTERVAL '1 day'),

('eeeeeeee-0000-0000-0000-000000000007','dddddddd-0000-0000-0000-000000000007',
 'Randonnée Fontainebleau','Sortie rando 15 km en forêt de Fontainebleau.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '68 days',NOW()-INTERVAL '7 days'),

('eeeeeeee-0000-0000-0000-000000000008','dddddddd-0000-0000-0000-000000000008',
 'Basketball 3x3','Tournoi de basketball 3x3. Équipes mixtes bienvenues.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '45 days',NOW()-INTERVAL '2 days'),

('eeeeeeee-0000-0000-0000-000000000009','dddddddd-0000-0000-0000-000000000009',
 'Méditation Pleine Conscience','Séances de méditation guidée 45 min. Débutants bienvenus.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '92 days',NOW()-INTERVAL '4 days'),

('eeeeeeee-0000-0000-0000-000000000010','dddddddd-0000-0000-0000-000000000010',
 'Judo Avancé','Entraînements judo pour préparation compétitions.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '35 days',NOW()-INTERVAL '6 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 6. SCHEDULES (10)
-- ============================================================
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at) VALUES
('ffffffff-0000-0000-0000-000000000001','eeeeeeee-0000-0000-0000-000000000001',
 'Champ de Mars','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2945,48.8566),4326),
 '2 Allée Adrienne Lecouvreur, 75007 Paris',FALSE,
 NOW()+INTERVAL '2 days',NOW()+INTERVAL '2 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=SA',10,NOW()-INTERVAL '70 days'),

('ffffffff-0000-0000-0000-000000000002','eeeeeeee-0000-0000-0000-000000000002',
 'Terrain synthétique Javel','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2755,48.8456),4326),
 'Rue Javel, 75015 Paris',TRUE,
 NOW()+INTERVAL '4 days',NOW()+INTERVAL '4 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA',10,NOW()-INTERVAL '48 days'),

('ffffffff-0000-0000-0000-000000000003','eeeeeeee-0000-0000-0000-000000000003',
 'Studio Zen Paris 11','INDOOR',ST_SetSRID(ST_MakePoint(2.3800,48.8600),4326),
 '42 Rue de la Roquette, 75011 Paris',TRUE,
 NOW()+INTERVAL '1 day',NOW()+INTERVAL '1 day'+INTERVAL '75 minutes',
 'FREQ=WEEKLY;BYDAY=MO,WE,FR',8,NOW()-INTERVAL '108 days'),

('ffffffff-0000-0000-0000-000000000004','eeeeeeee-0000-0000-0000-000000000004',
 'Grande Cascade Bois de Boulogne','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2411,48.8623),4326),
 'Allée de Longchamp, 75016 Paris',FALSE,
 NOW()+INTERVAL '6 days',NOW()+INTERVAL '6 days'+INTERVAL '3 hours',
 'FREQ=WEEKLY;BYDAY=SU',15,NOW()-INTERVAL '22 days'),

('ffffffff-0000-0000-0000-000000000005','eeeeeeee-0000-0000-0000-000000000005',
 'Piscine Molitor','INDOOR',ST_SetSRID(ST_MakePoint(2.2550,48.8476),4326),
 '2 Avenue de la Porte Molitor, 75016 Paris',TRUE,
 NOW()+INTERVAL '3 days',NOW()+INTERVAL '3 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=TU,TH',6,NOW()-INTERVAL '38 days'),

('ffffffff-0000-0000-0000-000000000006','eeeeeeee-0000-0000-0000-000000000006',
 'Dojo Paris 13','INDOOR',ST_SetSRID(ST_MakePoint(2.3650,48.8300),4326),
 '28 Rue de Tolbiac, 75013 Paris',TRUE,
 NOW()+INTERVAL '2 days',NOW()+INTERVAL '2 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=TU,TH',20,NOW()-INTERVAL '135 days'),

('ffffffff-0000-0000-0000-000000000007','eeeeeeee-0000-0000-0000-000000000007',
 'Gare de Lyon (départ)','OUTDOOR',ST_SetSRID(ST_MakePoint(2.3730,48.8450),4326),
 'Place Louis-Armand, 75012 Paris',TRUE,
 NOW()+INTERVAL '8 days',NOW()+INTERVAL '8 days'+INTERVAL '6 hours',
 NULL,25,NOW()-INTERVAL '65 days'),

('ffffffff-0000-0000-0000-000000000008','eeeeeeee-0000-0000-0000-000000000008',
 'Parc de Bercy','OUTDOOR',ST_SetSRID(ST_MakePoint(2.3810,48.8360),4326),
 '128 Quai de Bercy, 75012 Paris',FALSE,
 NOW()+INTERVAL '5 days',NOW()+INTERVAL '5 days'+INTERVAL '3 hours',
 'FREQ=WEEKLY;BYDAY=SA',12,NOW()-INTERVAL '43 days'),

('ffffffff-0000-0000-0000-000000000009','eeeeeeee-0000-0000-0000-000000000009',
 'Espace Méditation Marais','INDOOR',ST_SetSRID(ST_MakePoint(2.3590,48.8590),4326),
 '15 Rue des Francs-Bourgeois, 75004 Paris',TRUE,
 NOW()+INTERVAL '1 day',NOW()+INTERVAL '1 day'+INTERVAL '45 minutes',
 'FREQ=WEEKLY;BYDAY=MO,WE,FR',10,NOW()-INTERVAL '90 days'),

('ffffffff-0000-0000-0000-000000000010','eeeeeeee-0000-0000-0000-000000000010',
 'Gymnase Léo Lagrange','INDOOR',ST_SetSRID(ST_MakePoint(2.3450,48.8670),4326),
 '75 Boulevard de Clichy, 75009 Paris',TRUE,
 NOW()+INTERVAL '3 days',NOW()+INTERVAL '3 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=TU,TH,SA',15,NOW()-INTERVAL '33 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 7. PROGRAM_MEDIA (10)
-- ============================================================
INSERT INTO program_media (id, program_id, url, media_type, sort_order, created_at) VALUES
('11111111-1111-0000-0000-000000000001','eeeeeeee-0000-0000-0000-000000000001','https://images.unsplash.com/photo-1476480862126-209bfaa8edc8?w=800','IMAGE',0,NOW()-INTERVAL '74 days'),
('11111111-1111-0000-0000-000000000002','eeeeeeee-0000-0000-0000-000000000002','https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=800','IMAGE',0,NOW()-INTERVAL '49 days'),
('11111111-1111-0000-0000-000000000003','eeeeeeee-0000-0000-0000-000000000003','https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=800','IMAGE',0,NOW()-INTERVAL '109 days'),
('11111111-1111-0000-0000-000000000004','eeeeeeee-0000-0000-0000-000000000004','https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800','IMAGE',0,NOW()-INTERVAL '24 days'),
('11111111-1111-0000-0000-000000000005','eeeeeeee-0000-0000-0000-000000000005','https://images.unsplash.com/photo-1530549387789-4c1017266635?w=800','IMAGE',0,NOW()-INTERVAL '38 days'),
('11111111-1111-0000-0000-000000000006','eeeeeeee-0000-0000-0000-000000000006','https://images.unsplash.com/photo-1555597408-26bc8e548a46?w=800','IMAGE',0,NOW()-INTERVAL '138 days'),
('11111111-1111-0000-0000-000000000007','eeeeeeee-0000-0000-0000-000000000007','https://images.unsplash.com/photo-1551632811-561732d1e306?w=800','IMAGE',0,NOW()-INTERVAL '63 days'),
('11111111-1111-0000-0000-000000000008','eeeeeeee-0000-0000-0000-000000000008','https://images.unsplash.com/photo-1546519638-68e109498ffc?w=800','IMAGE',0,NOW()-INTERVAL '41 days'),
('11111111-1111-0000-0000-000000000009','eeeeeeee-0000-0000-0000-000000000009','https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800','IMAGE',0,NOW()-INTERVAL '88 days'),
('11111111-1111-0000-0000-000000000010','eeeeeeee-0000-0000-0000-000000000010','https://images.unsplash.com/photo-1555597408-26bc8e548a46?w=800','IMAGE',0,NOW()-INTERVAL '31 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 8. CONVERSATIONS (10)
-- ============================================================
INSERT INTO conversations (id, type, activity_context_id, created_at, last_message_at) VALUES
('22222222-2222-0000-0000-000000000001','DIRECT',NULL,NOW()-INTERVAL '50 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000002','DIRECT',NULL,NOW()-INTERVAL '30 days',NOW()-INTERVAL '3 hours'),
('22222222-2222-0000-0000-000000000003','DIRECT','cccccccc-0000-0000-0000-000000000004',NOW()-INTERVAL '20 days',NOW()-INTERVAL '2 days'),
('22222222-2222-0000-0000-000000000004','DIRECT',NULL,NOW()-INTERVAL '15 days',NOW()-INTERVAL '5 hours'),
('22222222-2222-0000-0000-000000000005','DIRECT','cccccccc-0000-0000-0000-000000000006',NOW()-INTERVAL '25 days',NOW()-INTERVAL '1 day'),
('22222222-2222-0000-0000-000000000006','DIRECT',NULL,NOW()-INTERVAL '10 days',NOW()-INTERVAL '30 minutes'),
('22222222-2222-0000-0000-000000000007','DIRECT','cccccccc-0000-0000-0000-000000000009',NOW()-INTERVAL '35 days',NOW()-INTERVAL '4 days'),
('22222222-2222-0000-0000-000000000008','DIRECT',NULL,NOW()-INTERVAL '18 days',NOW()-INTERVAL '6 hours'),
('22222222-2222-0000-0000-000000000009','DIRECT','cccccccc-0000-0000-0000-000000000002',NOW()-INTERVAL '12 days',NOW()-INTERVAL '2 hours'),
('22222222-2222-0000-0000-000000000010','DIRECT',NULL,NOW()-INTERVAL '8 days',NOW()-INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 9. CONVERSATION_MEMBERS (20 - 2 par conversation)
-- ============================================================
INSERT INTO conversation_members (conversation_id, user_id, joined_at, last_read_at) VALUES
('22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '50 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',NOW()-INTERVAL '50 days',NOW()-INTERVAL '2 hours'),
('22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '30 days',NOW()-INTERVAL '3 hours'),
('22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000004',NOW()-INTERVAL '30 days',NOW()-INTERVAL '4 hours'),
('22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000005',NOW()-INTERVAL '20 days',NOW()-INTERVAL '2 days'),
('22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000006',NOW()-INTERVAL '20 days',NOW()-INTERVAL '3 days'),
('22222222-2222-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000007',NOW()-INTERVAL '15 days',NOW()-INTERVAL '5 hours'),
('22222222-2222-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000008',NOW()-INTERVAL '15 days',NOW()-INTERVAL '6 hours'),
('22222222-2222-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000009',NOW()-INTERVAL '25 days',NOW()-INTERVAL '1 day'),
('22222222-2222-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000010',NOW()-INTERVAL '25 days',NOW()-INTERVAL '2 days'),
('22222222-2222-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '10 days',NOW()-INTERVAL '30 minutes'),
('22222222-2222-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '10 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000002',NOW()-INTERVAL '35 days',NOW()-INTERVAL '4 days'),
('22222222-2222-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000005',NOW()-INTERVAL '35 days',NOW()-INTERVAL '5 days'),
('22222222-2222-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000004',NOW()-INTERVAL '18 days',NOW()-INTERVAL '6 hours'),
('22222222-2222-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000007',NOW()-INTERVAL '18 days',NOW()-INTERVAL '7 hours'),
('22222222-2222-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000006',NOW()-INTERVAL '12 days',NOW()-INTERVAL '2 hours'),
('22222222-2222-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000009',NOW()-INTERVAL '12 days',NOW()-INTERVAL '3 hours'),
('22222222-2222-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000008',NOW()-INTERVAL '8 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000010',NOW()-INTERVAL '8 days',NOW()-INTERVAL '2 hours')
ON CONFLICT (conversation_id, user_id) DO NOTHING;

-- ============================================================
-- 10. MESSAGES (10)
-- ============================================================
INSERT INTO messages (id, conversation_id, sender_id, content, status, sent_at, read_at) VALUES
('33333333-3333-0000-0000-000000000001','22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Salut Alice ! Je suis intéressé par ton programme running débutant.',
 'READ',NOW()-INTERVAL '50 days',NOW()-INTERVAL '50 days'+INTERVAL '10 minutes'),

('33333333-3333-0000-0000-000000000002','22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000003',
 'Bonjour David, es-tu dispo pour une sortie vélo ce weekend ?',
 'READ',NOW()-INTERVAL '30 days',NOW()-INTERVAL '30 days'+INTERVAL '30 minutes'),

('33333333-3333-0000-0000-000000000003','22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000005',
 'Frank, j''aimerais essayer ton cours de karaté. C''est quand ?',
 'READ',NOW()-INTERVAL '20 days',NOW()-INTERVAL '20 days'+INTERVAL '1 hour'),

('33333333-3333-0000-0000-000000000004','22222222-2222-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000007',
 'Hugo, tu veux venir faire du basket samedi ?',
 'DELIVERED',NOW()-INTERVAL '15 days',NULL),

('33333333-3333-0000-0000-000000000005','22222222-2222-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000009',
 'Julien, ta séance de méditation m''intéresse beaucoup !',
 'READ',NOW()-INTERVAL '25 days',NOW()-INTERVAL '25 days'+INTERVAL '2 hours'),

('33333333-3333-0000-0000-000000000006','22222222-2222-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000001',
 'Claire, merci pour la séance de yoga, c''était super !',
 'READ',NOW()-INTERVAL '10 days',NOW()-INTERVAL '10 days'+INTERVAL '15 minutes'),

('33333333-3333-0000-0000-000000000007','22222222-2222-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000002',
 'Emma, on fait une sortie natation ensemble ?',
 'SENT',NOW()-INTERVAL '35 days',NULL),

('33333333-3333-0000-0000-000000000008','22222222-2222-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000004',
 'Grace, ta rando de dimanche est toujours d''actualité ?',
 'READ',NOW()-INTERVAL '18 days',NOW()-INTERVAL '18 days'+INTERVAL '45 minutes'),

('33333333-3333-0000-0000-000000000009','22222222-2222-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000006',
 'Isabelle, super session aujourd''hui, merci !',
 'READ',NOW()-INTERVAL '12 days',NOW()-INTERVAL '12 days'+INTERVAL '20 minutes'),

('33333333-3333-0000-0000-000000000010','22222222-2222-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000008',
 'Julien, on se voit au dojo demain ?',
 'DELIVERED',NOW()-INTERVAL '8 days',NULL)
ON CONFLICT (id) DO NOTHING;
