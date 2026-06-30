-- V12: Données de test (mot de passe commun: Test1234!)
-- UUIDs préfixes: aa=users, bb=categories, cc=activities, dd=user_activities,
--                ee=programs, ff=schedules, 11=media, 22=conversations,
--                33=messages, 44=reviews, 55=criteria, 66=peer_rec,
--                77=badges, 88=notifs, 99=notif_prefs, aa-aa=reports,
--                bb-bb=search_logs, cc-cc=progressions, dd-dd=device_tokens

-- ============================================================
-- 1. USERS
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
 'VERIFIED',NOW()-INTERVAL '45 days',NOW()-INTERVAL '45 days',NOW()-INTERVAL '5 hours',TRUE);

-- ============================================================
-- 2. CATEGORIES
-- ============================================================
INSERT INTO categories (id, name, icon, color_ramp) VALUES
('bbbbbbbb-0000-0000-0000-000000000001','Sports collectifs','team',    'blue-indigo'),
('bbbbbbbb-0000-0000-0000-000000000002','Sports individuels','person', 'green-teal'),
('bbbbbbbb-0000-0000-0000-000000000003','Bien-être',        'heart',   'purple-pink'),
('bbbbbbbb-0000-0000-0000-000000000004','Arts martiaux',    'shield',  'red-orange'),
('bbbbbbbb-0000-0000-0000-000000000005','Plein air',        'mountain','orange-yellow');

-- ============================================================
-- 3. ACTIVITIES
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
('cccccccc-0000-0000-0000-000000000010',NULL,'bbbbbbbb-0000-0000-0000-000000000005','Randonnée',  'randonnee',  'Marche en nature sur sentiers balisés.',              NULL,NOW()-INTERVAL '180 days');

-- ============================================================
-- 4. USER_ACTIVITIES
-- ============================================================
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at) VALUES
('dddddddd-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000005',TRUE, 'Coach running certifiée, tous niveaux','ADVANCED',    'GROUP',NOW()-INTERVAL '80 days'),
('dddddddd-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000006',TRUE, NULL,                                   'INTERMEDIATE','DUO',  NOW()-INTERVAL '70 days'),
('dddddddd-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000002','cccccccc-0000-0000-0000-000000000001',TRUE, 'Milieu de terrain, cherche équipe WE', 'INTERMEDIATE','GROUP',NOW()-INTERVAL '55 days'),
('dddddddd-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002','cccccccc-0000-0000-0000-000000000005',FALSE,NULL,                                   'BEGINNER',    'SOLO', NOW()-INTERVAL '40 days'),
('dddddddd-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000003','cccccccc-0000-0000-0000-000000000006',TRUE, 'Cours particuliers et petits groupes', 'ADVANCED',    'GROUP',NOW()-INTERVAL '115 days'),
('dddddddd-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000003','cccccccc-0000-0000-0000-000000000007',TRUE, NULL,                                   'ADVANCED',    'SOLO', NOW()-INTERVAL '100 days'),
('dddddddd-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000004','cccccccc-0000-0000-0000-000000000004',TRUE, 'Sorties route & VTT niveau interméd+', 'INTERMEDIATE','GROUP',NOW()-INTERVAL '28 days'),
('dddddddd-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000004','cccccccc-0000-0000-0000-000000000010',TRUE, NULL,                                   'BEGINNER',    'GROUP',NOW()-INTERVAL '20 days'),
('dddddddd-0000-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000005','cccccccc-0000-0000-0000-000000000003',TRUE, 'Entraînements endurance et technique', 'ADVANCED',    'GROUP',NOW()-INTERVAL '42 days'),
('dddddddd-0000-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000005','cccccccc-0000-0000-0000-000000000004',FALSE,NULL,                                   'INTERMEDIATE','SOLO', NOW()-INTERVAL '30 days');

-- ============================================================
-- 5. PROGRAMS
-- ============================================================
INSERT INTO programs (id, user_activity_id, title, description, embedding, status, is_public, archived_at, created_at, updated_at) VALUES
('eeeeeeee-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-000000000001',
 'Running Paris Débutants','Programme 8 semaines pour débuter le running en toute sécurité. Champ de Mars, sam 7h30.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '75 days',NOW()-INTERVAL '10 days'),

('eeeeeeee-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-000000000003',
 'Football 5x5 Samedi','Match amical 5c5 chaque samedi. Terrain synthétique. Tous niveaux bienvenus.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '50 days',NOW()-INTERVAL '5 days'),

('eeeeeeee-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-000000000005',
 'Yoga Hatha Matin','Séance yoga Hatha 75 min en petit groupe (max 8 pers.). Idéal pour commencer la journée.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '110 days',NOW()-INTERVAL '2 days'),

('eeeeeeee-0000-0000-0000-000000000004','dddddddd-0000-0000-0000-000000000007',
 'Sortie Vélo Bois de Boulogne','Sortie cyclisme dimanche matin, 30 km à rythme libre. RDV: Grande Cascade.',
 NULL,'PUBLISHED',TRUE,NULL,NOW()-INTERVAL '25 days',NOW()-INTERVAL '1 day'),

('eeeeeeee-0000-0000-0000-000000000005','dddddddd-0000-0000-0000-000000000009',
 'Natation Endurance','Entraînement natation endurance, 2000 m/session. Piscine Molitor, couloir réservé.',
 NULL,'DRAFT',FALSE,NULL,NOW()-INTERVAL '15 days',NULL);

-- ============================================================
-- 6. SCHEDULES
-- ============================================================
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at) VALUES
('ffffffff-0000-0000-0000-000000000001','eeeeeeee-0000-0000-0000-000000000001',
 'Champ de Mars','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2945,48.8566),4326),
 '2 Allée Adrienne Lecouvreur, 75007 Paris',FALSE,
 NOW()+INTERVAL '2 days',NOW()+INTERVAL '2 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=SA',10,NOW()-INTERVAL '70 days'),

('ffffffff-0000-0000-0000-000000000002','eeeeeeee-0000-0000-0000-000000000001',
 'Bois de Vincennes','OUTDOOR',ST_SetSRID(ST_MakePoint(2.4386,48.8443),4326),
 'Entrée Lac Daumesnil, 75012 Paris',FALSE,
 NOW()+INTERVAL '9 days',NOW()+INTERVAL '9 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=SA',10,NOW()-INTERVAL '68 days'),

('ffffffff-0000-0000-0000-000000000003','eeeeeeee-0000-0000-0000-000000000002',
 'Terrain synthétique Javel','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2755,48.8456),4326),
 'Rue Javel, 75015 Paris',TRUE,
 NOW()+INTERVAL '4 days',NOW()+INTERVAL '4 days'+INTERVAL '2 hours',
 'FREQ=WEEKLY;BYDAY=SA',10,NOW()-INTERVAL '48 days'),

('ffffffff-0000-0000-0000-000000000004','eeeeeeee-0000-0000-0000-000000000003',
 'Studio Zen Paris 11','INDOOR',ST_SetSRID(ST_MakePoint(2.3800,48.8600),4326),
 '42 Rue de la Roquette, 75011 Paris',TRUE,
 NOW()+INTERVAL '1 day',NOW()+INTERVAL '1 day'+INTERVAL '75 minutes',
 'FREQ=WEEKLY;BYDAY=MO,WE,FR',8,NOW()-INTERVAL '108 days'),

('ffffffff-0000-0000-0000-000000000005','eeeeeeee-0000-0000-0000-000000000003',
 'Jardin des Tuileries','OUTDOOR',ST_SetSRID(ST_MakePoint(2.3317,48.8635),4326),
 NULL,FALSE,
 NOW()+INTERVAL '5 days',NOW()+INTERVAL '5 days'+INTERVAL '75 minutes',
 'FREQ=WEEKLY;BYDAY=SU',12,NOW()-INTERVAL '100 days'),

('ffffffff-0000-0000-0000-000000000006','eeeeeeee-0000-0000-0000-000000000004',
 'Grande Cascade Bois de Boulogne','OUTDOOR',ST_SetSRID(ST_MakePoint(2.2411,48.8623),4326),
 'Allée de Longchamp, 75016 Paris',FALSE,
 NOW()+INTERVAL '6 days',NOW()+INTERVAL '6 days'+INTERVAL '3 hours',
 'FREQ=WEEKLY;BYDAY=SU',15,NOW()-INTERVAL '22 days'),

('ffffffff-0000-0000-0000-000000000007','eeeeeeee-0000-0000-0000-000000000005',
 'Piscine Molitor','INDOOR',ST_SetSRID(ST_MakePoint(2.2550,48.8476),4326),
 '2 Avenue de la Porte Molitor, 75016 Paris',TRUE,
 NOW()+INTERVAL '3 days',NOW()+INTERVAL '3 days'+INTERVAL '90 minutes',
 'FREQ=WEEKLY;BYDAY=TU,TH',6,NOW()-INTERVAL '13 days');

-- ============================================================
-- 7. PROGRAM_MEDIA
-- ============================================================
INSERT INTO program_media (id, program_id, url, media_type, sort_order, created_at) VALUES
('11111111-1111-0000-0000-000000000001','eeeeeeee-0000-0000-0000-000000000001','https://images.unsplash.com/photo-1476480862126-209bfaa8edc8?w=800','IMAGE',0,NOW()-INTERVAL '74 days'),
('11111111-1111-0000-0000-000000000002','eeeeeeee-0000-0000-0000-000000000001','https://images.unsplash.com/photo-1571008887538-b36bb32f4571?w=800','IMAGE',1,NOW()-INTERVAL '74 days'),
('11111111-1111-0000-0000-000000000003','eeeeeeee-0000-0000-0000-000000000002','https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=800','IMAGE',0,NOW()-INTERVAL '49 days'),
('11111111-1111-0000-0000-000000000004','eeeeeeee-0000-0000-0000-000000000003','https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=800','IMAGE',0,NOW()-INTERVAL '109 days'),
('11111111-1111-0000-0000-000000000005','eeeeeeee-0000-0000-0000-000000000003','https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800','IMAGE',1,NOW()-INTERVAL '109 days'),
('11111111-1111-0000-0000-000000000006','eeeeeeee-0000-0000-0000-000000000004','https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800','IMAGE',0,NOW()-INTERVAL '24 days'),
('11111111-1111-0000-0000-000000000007','eeeeeeee-0000-0000-0000-000000000005','https://images.unsplash.com/photo-1530549387789-4c1017266635?w=800','IMAGE',0,NOW()-INTERVAL '14 days');

-- ============================================================
-- 8. CONVERSATIONS
-- ============================================================
INSERT INTO conversations (id, type, activity_context_id, created_at, last_message_at) VALUES
('22222222-2222-0000-0000-000000000001','DIRECT',NULL,                                           NOW()-INTERVAL '50 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000002','DIRECT',NULL,                                           NOW()-INTERVAL '30 days',NOW()-INTERVAL '3 hours'),
('22222222-2222-0000-0000-000000000003','DIRECT','cccccccc-0000-0000-0000-000000000004',          NOW()-INTERVAL '20 days',NOW()-INTERVAL '2 days');

-- ============================================================
-- 9. CONVERSATION_MEMBERS
-- ============================================================
INSERT INTO conversation_members (conversation_id, user_id, joined_at, last_read_at) VALUES
('22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '50 days',NOW()-INTERVAL '1 hour'),
('22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',NOW()-INTERVAL '50 days',NOW()-INTERVAL '2 hours'),
('22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '30 days',NOW()-INTERVAL '3 hours'),
('22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000005',NOW()-INTERVAL '30 days',NOW()-INTERVAL '4 hours'),
('22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000002',NOW()-INTERVAL '20 days',NOW()-INTERVAL '2 days'),
('22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000004',NOW()-INTERVAL '20 days',NOW()-INTERVAL '3 days');

-- ============================================================
-- 10. MESSAGES
-- ============================================================
INSERT INTO messages (id, conversation_id, sender_id, content, status, sent_at, read_at) VALUES
('33333333-3333-0000-0000-000000000001','22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Salut Alice ! Je suis intéressé par ton programme running débutant.',
 'READ',NOW()-INTERVAL '50 days',NOW()-INTERVAL '50 days'+INTERVAL '10 minutes'),

('33333333-3333-0000-0000-000000000002','22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',
 'Bonjour Bob ! Avec plaisir, prochain départ samedi à 7h30 au Champ de Mars.',
 'READ',NOW()-INTERVAL '50 days'+INTERVAL '15 minutes',NOW()-INTERVAL '49 days'),

('33333333-3333-0000-0000-000000000003','22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Super ! Je serai là. Qu''est-ce que je dois apporter ?',
 'READ',NOW()-INTERVAL '49 days'+INTERVAL '2 hours',NOW()-INTERVAL '48 days'),

('33333333-3333-0000-0000-000000000004','22222222-2222-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',
 'Juste tes chaussures de running et une bouteille d''eau. À samedi !',
 'SENT',NOW()-INTERVAL '1 hour',NULL),

('33333333-3333-0000-0000-000000000005','22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000005',
 'Bonjour Claire, j''aimerais essayer le yoga, je n''ai jamais pratiqué.',
 'READ',NOW()-INTERVAL '30 days',NOW()-INTERVAL '29 days'),

('33333333-3333-0000-0000-000000000006','22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000003',
 'Parfait, mon cours du lundi matin est idéal pour les débutantes. Rejoins-nous !',
 'READ',NOW()-INTERVAL '29 days'+INTERVAL '1 hour',NOW()-INTERVAL '28 days'),

('33333333-3333-0000-0000-000000000007','22222222-2222-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000005',
 'J''ai adoré la première séance, merci beaucoup Claire !',
 'READ',NOW()-INTERVAL '3 hours',NOW()-INTERVAL '3 hours'+INTERVAL '20 minutes'),

('33333333-3333-0000-0000-000000000008','22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000002',
 'Hey David, tu fais aussi du vélo ? On pourrait faire une sortie ensemble !',
 'READ',NOW()-INTERVAL '20 days',NOW()-INTERVAL '19 days'),

('33333333-3333-0000-0000-000000000009','22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000004',
 'Avec plaisir ! Je fais une sortie au Bois dimanche, tu viens ?',
 'READ',NOW()-INTERVAL '19 days'+INTERVAL '3 hours',NOW()-INTERVAL '18 days'),

('33333333-3333-0000-0000-000000000010','22222222-2222-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000002',
 'Je suis partant ! À quelle heure on se retrouve ?',
 'SENT',NOW()-INTERVAL '2 days',NULL);

-- ============================================================
-- 11. REVIEWS
-- ============================================================
INSERT INTO reviews (id, program_id, reviewer_id, interaction_proof_id, score, comment, created_at) VALUES
('44444444-4444-0000-0000-000000000001',
 'eeeeeeee-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 '22222222-2222-0000-0000-000000000001',
 4.5,'Programme très bien structuré, Alice est une coach à l''écoute. J''ai progressé rapidement.',
 NOW()-INTERVAL '20 days'),

('44444444-4444-0000-0000-000000000002',
 'eeeeeeee-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000005',
 '22222222-2222-0000-0000-000000000002',
 5.0,'Claire est une instructrice exceptionnelle. Atmosphère zen, postures bien expliquées.',
 NOW()-INTERVAL '10 days'),

('44444444-4444-0000-0000-000000000003',
 'eeeeeeee-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002',
 '22222222-2222-0000-0000-000000000003',
 4.0,'Sortie très agréable, David connaît bien les parcours. Bon rythme pour les intermédiaires.',
 NOW()-INTERVAL '5 days');

-- ============================================================
-- 12. REVIEW_CRITERIA
-- ============================================================
INSERT INTO review_criteria (id, review_id, criterion_key, score) VALUES
('55555555-5555-0000-0000-000000000001','44444444-4444-0000-0000-000000000001','ponctualite',  5.0),
('55555555-5555-0000-0000-000000000002','44444444-4444-0000-0000-000000000001','pedagogie',    5.0),
('55555555-5555-0000-0000-000000000003','44444444-4444-0000-0000-000000000001','securite',     4.0),
('55555555-5555-0000-0000-000000000004','44444444-4444-0000-0000-000000000002','ponctualite',  5.0),
('55555555-5555-0000-0000-000000000005','44444444-4444-0000-0000-000000000002','pedagogie',    5.0),
('55555555-5555-0000-0000-000000000006','44444444-4444-0000-0000-000000000002','ambiance',     5.0),
('55555555-5555-0000-0000-000000000007','44444444-4444-0000-0000-000000000003','ponctualite',  4.0),
('55555555-5555-0000-0000-000000000008','44444444-4444-0000-0000-000000000003','securite',     4.0),
('55555555-5555-0000-0000-000000000009','44444444-4444-0000-0000-000000000003','communication',4.0);

-- ============================================================
-- 13. PEER_RECOMMENDATIONS
-- ============================================================
INSERT INTO peer_recommendations (id, from_user_id, to_user_id, interaction_proof_id, comment, created_at) VALUES
('66666666-6666-0000-0000-000000000001',
 'aaaaaaaa-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001',
 '22222222-2222-0000-0000-000000000001',
 'Alice est une coach exceptionnelle, très pro et bienveillante.',
 NOW()-INTERVAL '18 days'),

('66666666-6666-0000-0000-000000000002',
 'aaaaaaaa-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000003',
 '22222222-2222-0000-0000-000000000002',
 'Claire m''a initié au yoga avec patience. Je la recommande vivement !',
 NOW()-INTERVAL '8 days'),

('66666666-6666-0000-0000-000000000003',
 'aaaaaaaa-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002',
 '22222222-2222-0000-0000-000000000003',
 'Bob est un excellent partenaire de sport, motivant et sympa.',
 NOW()-INTERVAL '3 days');

-- ============================================================
-- 14. BADGES
-- ============================================================
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon) VALUES
('77777777-7777-0000-0000-000000000001','FIRST_PROGRAM',   'CREATION',  'Premier Programme','PROGRAMS_CREATED',       1, 'star'),
('77777777-7777-0000-0000-000000000002','COACH_PRO',       'CREATION',  'Coach Pro',        'PROGRAMS_CREATED',       5, 'trophy'),
('77777777-7777-0000-0000-000000000003','SOCIAL_BUTTERFLY','SOCIAL',    'Papillon Social',  'CONVERSATIONS_STARTED', 10, 'butterfly'),
('77777777-7777-0000-0000-000000000004','TOP_RATED',       'REPUTATION','Très Bien Noté',   'AVERAGE_REVIEW_SCORE',   4, 'medal'),
('77777777-7777-0000-0000-000000000005','EXPLORER',        'ACTIVITY',  'Explorateur',      'ACTIVITIES_REGISTERED',  3, 'compass');

-- ============================================================
-- 15. BADGE_AWARDS
-- ============================================================
INSERT INTO badge_awards (badge_id, user_id, awarded_at) VALUES
('77777777-7777-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '75 days'),
('77777777-7777-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',NOW()-INTERVAL '48 days'),
('77777777-7777-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '109 days'),
('77777777-7777-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000004',NOW()-INTERVAL '24 days'),
('77777777-7777-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '15 days'),
('77777777-7777-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '7 days'),
('77777777-7777-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000001',NOW()-INTERVAL '70 days'),
('77777777-7777-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000003',NOW()-INTERVAL '100 days');

-- ============================================================
-- 16. NOTIFICATIONS
-- ============================================================
INSERT INTO notifications (id, user_id, type, channel, payload, is_read, sent_at, read_at) VALUES
('88888888-8888-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','NEW_MESSAGE',      'PUSH',  '{"from":"Bob Martin","preview":"Salut Alice !"}',               TRUE, NOW()-INTERVAL '50 days',  NOW()-INTERVAL '49 days'),
('88888888-8888-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','NEW_REVIEW',       'PUSH',  '{"reviewer":"Bob Martin","score":4.5}',                         TRUE, NOW()-INTERVAL '20 days',  NOW()-INTERVAL '19 days'),
('88888888-8888-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000001','NEW_BADGE',        'PUSH',  '{"badge":"TOP_RATED","label":"Très Bien Noté"}',                TRUE, NOW()-INTERVAL '15 days',  NOW()-INTERVAL '14 days'),
('88888888-8888-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002','NEW_MESSAGE',      'PUSH',  '{"from":"Alice Dupont","preview":"Bonjour Bob !"}',             TRUE, NOW()-INTERVAL '50 days',  NOW()-INTERVAL '49 days'),
('88888888-8888-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000002','PROGRAM_REMINDER', 'PUSH',  '{"program":"Running Paris","starts_in":"1 jour"}',              FALSE,NOW()-INTERVAL '1 day',   NULL),
('88888888-8888-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000003','NEW_MESSAGE',      'PUSH',  '{"from":"Emma Wilson","preview":"Bonjour Claire"}',             TRUE, NOW()-INTERVAL '30 days',  NOW()-INTERVAL '29 days'),
('88888888-8888-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000003','NEW_REVIEW',       'PUSH',  '{"reviewer":"Emma Wilson","score":5.0}',                        TRUE, NOW()-INTERVAL '10 days',  NOW()-INTERVAL '9 days'),
('88888888-8888-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000004','NEW_MESSAGE',      'PUSH',  '{"from":"Bob Martin","preview":"Hey David"}',                   TRUE, NOW()-INTERVAL '20 days',  NOW()-INTERVAL '19 days'),
('88888888-8888-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000005','PROGRAM_REMINDER', 'IN_APP','{"program":"Yoga Hatha Matin","starts_in":"2 heures"}',         FALSE,NOW()-INTERVAL '2 hours',  NULL),
('88888888-8888-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000005','NEW_PEER_REC',     'PUSH',  '{"from":"Claire Lebrun"}',                                      TRUE, NOW()-INTERVAL '8 days',   NOW()-INTERVAL '7 days');

-- ============================================================
-- 17. NOTIFICATION_PREFS
-- ============================================================
INSERT INTO notification_prefs (id, user_id, notification_type, email_enabled, push_enabled, frequency) VALUES
('99999999-9999-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','NEW_MESSAGE',      TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','NEW_REVIEW',       TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000001','PROGRAM_REMINDER', FALSE,TRUE, 'DAILY'),
('99999999-9999-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002','NEW_MESSAGE',      TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000002','PROGRAM_REMINDER', FALSE,TRUE, 'DAILY'),
('99999999-9999-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000003','NEW_MESSAGE',      TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000003','NEW_REVIEW',       TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000004','NEW_MESSAGE',      FALSE,TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-000000000005','NEW_MESSAGE',      TRUE, TRUE, 'IMMEDIATE'),
('99999999-9999-0000-0000-000000000010','aaaaaaaa-0000-0000-0000-000000000005','NEW_PEER_REC',     TRUE, FALSE,'WEEKLY');

-- ============================================================
-- 18. REPORTS
-- ============================================================
INSERT INTO reports (id, reporter_id, target_type, target_id, reason, status, created_at, resolved_at) VALUES
('aaaaaaaa-aaaa-0000-0000-000000000001',
 'aaaaaaaa-0000-0000-0000-000000000002','USER',
 'aaaaaaaa-0000-0000-0000-000000000004',
 'SPAM','OPEN',NOW()-INTERVAL '5 days',NULL),

('aaaaaaaa-aaaa-0000-0000-000000000002',
 'aaaaaaaa-0000-0000-0000-000000000005','MESSAGE',
 '33333333-3333-0000-0000-000000000009',
 'INAPPROPRIATE_CONTENT','RESOLVED',NOW()-INTERVAL '15 days',NOW()-INTERVAL '10 days'),

('aaaaaaaa-aaaa-0000-0000-000000000003',
 'aaaaaaaa-0000-0000-0000-000000000001','PROGRAM',
 'eeeeeeee-0000-0000-0000-000000000002',
 'MISLEADING_INFORMATION','DISMISSED',NOW()-INTERVAL '25 days',NOW()-INTERVAL '20 days');

-- ============================================================
-- 19. SEARCH_LOGS
-- ============================================================
INSERT INTO search_logs (id, user_id, raw_query, parsed_intent, query_embedding, results_count, created_at) VALUES
('bbbbbbbb-bbbb-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'running débutant paris','{"activity":"running","level":"BEGINNER","location":"paris"}',NULL,3,NOW()-INTERVAL '45 days'),

('bbbbbbbb-bbbb-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000005',
 'yoga matin paris 11','{"activity":"yoga","time":"morning","location":"paris 11"}',NULL,2,NOW()-INTERVAL '28 days'),

('bbbbbbbb-bbbb-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000002',
 'vélo bois de boulogne','{"activity":"cyclisme","location":"bois de boulogne"}',NULL,1,NOW()-INTERVAL '18 days'),

('bbbbbbbb-bbbb-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000004',
 'natation piscine 16e','{"activity":"natation","location":"16e arrondissement"}',NULL,1,NOW()-INTERVAL '10 days'),

('bbbbbbbb-bbbb-0000-0000-000000000005',NULL,
 'football amateur paris','{"activity":"football","level":"ANY","location":"paris"}',NULL,4,NOW()-INTERVAL '3 days');

-- ============================================================
-- 20. PROGRESSION_ENTRIES
-- ============================================================
INSERT INTO progression_entries (id, program_id, user_id, title, content, metrics, is_public, created_at) VALUES
('cccccccc-cccc-0000-0000-000000000001','eeeeeeee-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Semaine 1','Première sortie ! 3 km sans m''arrêter. Difficile mais motivant.',ARRAY[3.0,28.0,145.0],TRUE,NOW()-INTERVAL '43 days'),

('cccccccc-cccc-0000-0000-000000000002','eeeeeeee-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Semaine 3','5 km à 6:30/km. Nette progression, moins d''essoufflement.',ARRAY[5.0,32.5,138.0],TRUE,NOW()-INTERVAL '29 days'),

('cccccccc-cccc-0000-0000-000000000003','eeeeeeee-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002',
 'Semaine 6','8 km ! Je commence vraiment à apprécier le running.',ARRAY[8.0,50.0,135.0],TRUE,NOW()-INTERVAL '8 days'),

('cccccccc-cccc-0000-0000-000000000004','eeeeeeee-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000005',
 'Cours 1','Découverte du yoga. Postures de base, respiration ujjayi.',ARRAY[1.0],TRUE,NOW()-INTERVAL '27 days'),

('cccccccc-cccc-0000-0000-000000000005','eeeeeeee-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000005',
 'Cours 5','La posture du guerrier I commence à se stabiliser.',ARRAY[5.0],TRUE,NOW()-INTERVAL '13 days'),

('cccccccc-cccc-0000-0000-000000000006','eeeeeeee-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000002',
 'Sortie 1','Premier tour du Bois en vélo, 28 km. Super ambiance !',ARRAY[28.0,75.0,22.0],FALSE,NOW()-INTERVAL '18 days'),

('cccccccc-cccc-0000-0000-000000000007','eeeeeeee-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000005',
 'Session 1','2000 m : 500 crawl + 500 dos + 500 brasse + 500 crawl.',ARRAY[2000.0,42.0],FALSE,NOW()-INTERVAL '13 days'),

('cccccccc-cccc-0000-0000-000000000008','eeeeeeee-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',
 'Bilan S1 coach','6 participants motivés, bon départ de groupe.',ARRAY[6.0,3.5],TRUE,NOW()-INTERVAL '42 days'),

('cccccccc-cccc-0000-0000-000000000009','eeeeeeee-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000002',
 'Match 1','4-3 pour notre équipe. 2 buts et 1 passe déc. !',ARRAY[90.0,3.0,1.0],TRUE,NOW()-INTERVAL '46 days'),

('cccccccc-cccc-0000-0000-000000000010','eeeeeeee-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000004',
 'Recap Vélo','Bonne sortie, 12 participants, météo idéale.',ARRAY[12.0,32.0],TRUE,NOW()-INTERVAL '17 days');

-- ============================================================
-- 21. DEVICE_TOKENS
-- ============================================================
INSERT INTO device_tokens (id, user_id, token, platform, device_name, created_at, last_used_at) VALUES
('dddddddd-dddd-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','fcm_alice_ios_xK9mP2nQ4rL7vY1cT8jW3bA6hU5eD0','IOS',    'iPhone 15 Pro',     NOW()-INTERVAL '85 days', NOW()-INTERVAL '2 hours'),
('dddddddd-dddd-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000002','fcm_bob_android_zR3sE8wN1kM4pO7yI2uG6tF9cB5qA','ANDROID','Samsung Galaxy S24',NOW()-INTERVAL '55 days', NOW()-INTERVAL '1 day'),
('dddddddd-dddd-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000003','fcm_claire_ios_vH4jL0nK7mY2cT5bW8rA1dU3eQ6pX','IOS',    'iPhone 14',         NOW()-INTERVAL '115 days',NOW()-INTERVAL '30 minutes'),
('dddddddd-dddd-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000004','fcm_david_android_wJ6kF1nR3sE9mP4yI7uG2tB8cA','ANDROID','Google Pixel 8',    NOW()-INTERVAL '28 days', NOW()-INTERVAL '3 days'),
('dddddddd-dddd-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-000000000005','fcm_emma_ios_xM2pO7yI4uG1tF9cB3qA8wN5kR6sE0Z','IOS',   'iPhone 15',         NOW()-INTERVAL '40 days', NOW()-INTERVAL '5 hours');
