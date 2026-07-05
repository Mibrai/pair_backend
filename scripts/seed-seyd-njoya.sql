-- =============================================================
-- SEED DATA for seyd.njoya@icloud.com  (utilisateur déjà existant)
-- Tous les UUIDs sont en hex valide (0-9, a-f uniquement)
-- =============================================================

DO $$
DECLARE
    -- User récupéré depuis la base
    v_user_id        UUID;

    -- Category récupérée depuis la base (première disponible)
    v_cat_sport_id   UUID;

    -- Activities (10)  -- préfixe aa10
    v_act1  UUID := 'aa100000-0000-0000-0000-000000000001';
    v_act2  UUID := 'aa100000-0000-0000-0000-000000000002';
    v_act3  UUID := 'aa100000-0000-0000-0000-000000000003';
    v_act4  UUID := 'aa100000-0000-0000-0000-000000000004';
    v_act5  UUID := 'aa100000-0000-0000-0000-000000000005';
    v_act6  UUID := 'aa100000-0000-0000-0000-000000000006';
    v_act7  UUID := 'aa100000-0000-0000-0000-000000000007';
    v_act8  UUID := 'aa100000-0000-0000-0000-000000000008';
    v_act9  UUID := 'aa100000-0000-0000-0000-000000000009';
    v_act10 UUID := 'aa100000-0000-0000-0000-000000000010';

    -- User activities (10)  -- préfixe aa20
    v_ua1  UUID := 'aa200000-0000-0000-0000-000000000001';
    v_ua2  UUID := 'aa200000-0000-0000-0000-000000000002';
    v_ua3  UUID := 'aa200000-0000-0000-0000-000000000003';
    v_ua4  UUID := 'aa200000-0000-0000-0000-000000000004';
    v_ua5  UUID := 'aa200000-0000-0000-0000-000000000005';
    v_ua6  UUID := 'aa200000-0000-0000-0000-000000000006';
    v_ua7  UUID := 'aa200000-0000-0000-0000-000000000007';
    v_ua8  UUID := 'aa200000-0000-0000-0000-000000000008';
    v_ua9  UUID := 'aa200000-0000-0000-0000-000000000009';
    v_ua10 UUID := 'aa200000-0000-0000-0000-000000000010';

    -- Programs (10)  -- préfixe aa30
    v_prog1  UUID := 'aa300000-0000-0000-0000-000000000001';
    v_prog2  UUID := 'aa300000-0000-0000-0000-000000000002';
    v_prog3  UUID := 'aa300000-0000-0000-0000-000000000003';
    v_prog4  UUID := 'aa300000-0000-0000-0000-000000000004';
    v_prog5  UUID := 'aa300000-0000-0000-0000-000000000005';
    v_prog6  UUID := 'aa300000-0000-0000-0000-000000000006';
    v_prog7  UUID := 'aa300000-0000-0000-0000-000000000007';
    v_prog8  UUID := 'aa300000-0000-0000-0000-000000000008';
    v_prog9  UUID := 'aa300000-0000-0000-0000-000000000009';
    v_prog10 UUID := 'aa300000-0000-0000-0000-000000000010';

    -- Schedules (10)  -- préfixe aa40
    v_sched1  UUID := 'aa400000-0000-0000-0000-000000000001';
    v_sched2  UUID := 'aa400000-0000-0000-0000-000000000002';
    v_sched3  UUID := 'aa400000-0000-0000-0000-000000000003';
    v_sched4  UUID := 'aa400000-0000-0000-0000-000000000004';
    v_sched5  UUID := 'aa400000-0000-0000-0000-000000000005';
    v_sched6  UUID := 'aa400000-0000-0000-0000-000000000006';
    v_sched7  UUID := 'aa400000-0000-0000-0000-000000000007';
    v_sched8  UUID := 'aa400000-0000-0000-0000-000000000008';
    v_sched9  UUID := 'aa400000-0000-0000-0000-000000000009';
    v_sched10 UUID := 'aa400000-0000-0000-0000-000000000010';

    -- Program media (10)  -- préfixe aa50
    v_med1  UUID := 'aa500000-0000-0000-0000-000000000001';
    v_med2  UUID := 'aa500000-0000-0000-0000-000000000002';
    v_med3  UUID := 'aa500000-0000-0000-0000-000000000003';
    v_med4  UUID := 'aa500000-0000-0000-0000-000000000004';
    v_med5  UUID := 'aa500000-0000-0000-0000-000000000005';
    v_med6  UUID := 'aa500000-0000-0000-0000-000000000006';
    v_med7  UUID := 'aa500000-0000-0000-0000-000000000007';
    v_med8  UUID := 'aa500000-0000-0000-0000-000000000008';
    v_med9  UUID := 'aa500000-0000-0000-0000-000000000009';
    v_med10 UUID := 'aa500000-0000-0000-0000-000000000010';

    -- User programs (10)  -- préfixe aa60
    v_up1  UUID := 'aa600000-0000-0000-0000-000000000001';
    v_up2  UUID := 'aa600000-0000-0000-0000-000000000002';
    v_up3  UUID := 'aa600000-0000-0000-0000-000000000003';
    v_up4  UUID := 'aa600000-0000-0000-0000-000000000004';
    v_up5  UUID := 'aa600000-0000-0000-0000-000000000005';
    v_up6  UUID := 'aa600000-0000-0000-0000-000000000006';
    v_up7  UUID := 'aa600000-0000-0000-0000-000000000007';
    v_up8  UUID := 'aa600000-0000-0000-0000-000000000008';
    v_up9  UUID := 'aa600000-0000-0000-0000-000000000009';
    v_up10 UUID := 'aa600000-0000-0000-0000-000000000010';

    -- Program activities (10)  -- préfixe aa70
    v_pa1  UUID := 'aa700000-0000-0000-0000-000000000001';
    v_pa2  UUID := 'aa700000-0000-0000-0000-000000000002';
    v_pa3  UUID := 'aa700000-0000-0000-0000-000000000003';
    v_pa4  UUID := 'aa700000-0000-0000-0000-000000000004';
    v_pa5  UUID := 'aa700000-0000-0000-0000-000000000005';
    v_pa6  UUID := 'aa700000-0000-0000-0000-000000000006';
    v_pa7  UUID := 'aa700000-0000-0000-0000-000000000007';
    v_pa8  UUID := 'aa700000-0000-0000-0000-000000000008';
    v_pa9  UUID := 'aa700000-0000-0000-0000-000000000009';
    v_pa10 UUID := 'aa700000-0000-0000-0000-000000000010';

    -- Conversations (10)  -- préfixe aa80
    v_conv1  UUID := 'aa800000-0000-0000-0000-000000000001';
    v_conv2  UUID := 'aa800000-0000-0000-0000-000000000002';
    v_conv3  UUID := 'aa800000-0000-0000-0000-000000000003';
    v_conv4  UUID := 'aa800000-0000-0000-0000-000000000004';
    v_conv5  UUID := 'aa800000-0000-0000-0000-000000000005';
    v_conv6  UUID := 'aa800000-0000-0000-0000-000000000006';
    v_conv7  UUID := 'aa800000-0000-0000-0000-000000000007';
    v_conv8  UUID := 'aa800000-0000-0000-0000-000000000008';
    v_conv9  UUID := 'aa800000-0000-0000-0000-000000000009';
    v_conv10 UUID := 'aa800000-0000-0000-0000-000000000010';

    -- Reviews (10)  -- préfixe aa90
    v_rev1  UUID := 'aa900000-0000-0000-0000-000000000001';
    v_rev2  UUID := 'aa900000-0000-0000-0000-000000000002';
    v_rev3  UUID := 'aa900000-0000-0000-0000-000000000003';
    v_rev4  UUID := 'aa900000-0000-0000-0000-000000000004';
    v_rev5  UUID := 'aa900000-0000-0000-0000-000000000005';
    v_rev6  UUID := 'aa900000-0000-0000-0000-000000000006';
    v_rev7  UUID := 'aa900000-0000-0000-0000-000000000007';
    v_rev8  UUID := 'aa900000-0000-0000-0000-000000000008';
    v_rev9  UUID := 'aa900000-0000-0000-0000-000000000009';
    v_rev10 UUID := 'aa900000-0000-0000-0000-000000000010';

    -- Reviewers : 5 autres users récupérés dynamiquement (hors seyd)
    v_alice  UUID;
    v_bob    UUID;
    v_claire UUID;
    v_david  UUID;
    v_emma   UUID;

    -- Review criteria (10)  -- préfixe aab0
    v_rc1  UUID := 'aab00000-0000-0000-0000-000000000001';
    v_rc2  UUID := 'aab00000-0000-0000-0000-000000000002';
    v_rc3  UUID := 'aab00000-0000-0000-0000-000000000003';
    v_rc4  UUID := 'aab00000-0000-0000-0000-000000000004';
    v_rc5  UUID := 'aab00000-0000-0000-0000-000000000005';
    v_rc6  UUID := 'aab00000-0000-0000-0000-000000000006';
    v_rc7  UUID := 'aab00000-0000-0000-0000-000000000007';
    v_rc8  UUID := 'aab00000-0000-0000-0000-000000000008';
    v_rc9  UUID := 'aab00000-0000-0000-0000-000000000009';
    v_rc10 UUID := 'aab00000-0000-0000-0000-000000000010';

    -- Progression entries (10)  -- préfixe aac0
    v_pe1  UUID := 'aac00000-0000-0000-0000-000000000001';
    v_pe2  UUID := 'aac00000-0000-0000-0000-000000000002';
    v_pe3  UUID := 'aac00000-0000-0000-0000-000000000003';
    v_pe4  UUID := 'aac00000-0000-0000-0000-000000000004';
    v_pe5  UUID := 'aac00000-0000-0000-0000-000000000005';
    v_pe6  UUID := 'aac00000-0000-0000-0000-000000000006';
    v_pe7  UUID := 'aac00000-0000-0000-0000-000000000007';
    v_pe8  UUID := 'aac00000-0000-0000-0000-000000000008';
    v_pe9  UUID := 'aac00000-0000-0000-0000-000000000009';
    v_pe10 UUID := 'aac00000-0000-0000-0000-000000000010';

BEGIN

-- =============================================================
-- 0. RÉCUPÉRATION de l'utilisateur et d'une catégorie existants
-- =============================================================
SELECT id INTO v_user_id FROM users WHERE email = 'seyd.njoya@icloud.com';
IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Utilisateur seyd.njoya@icloud.com introuvable dans la base.';
END IF;

SELECT id INTO v_cat_sport_id FROM categories LIMIT 1;
IF v_cat_sport_id IS NULL THEN
    RAISE EXCEPTION 'Aucune catégorie trouvée dans la base.';
END IF;

-- Récupération de 5 autres utilisateurs pour jouer le rôle de reviewers
SELECT id INTO v_alice  FROM users WHERE id != v_user_id ORDER BY created_at LIMIT 1 OFFSET 0;
SELECT id INTO v_bob    FROM users WHERE id != v_user_id ORDER BY created_at LIMIT 1 OFFSET 1;
SELECT id INTO v_claire FROM users WHERE id != v_user_id ORDER BY created_at LIMIT 1 OFFSET 2;
SELECT id INTO v_david  FROM users WHERE id != v_user_id ORDER BY created_at LIMIT 1 OFFSET 3;
SELECT id INTO v_emma   FROM users WHERE id != v_user_id ORDER BY created_at LIMIT 1 OFFSET 4;

IF v_alice IS NULL OR v_bob IS NULL OR v_claire IS NULL OR v_david IS NULL OR v_emma IS NULL THEN
    RAISE EXCEPTION 'Pas assez d''autres utilisateurs en base (minimum 5 requis pour les reviews).';
END IF;

-- =============================================================
-- 1. ACTIVITIES (10 activités)
-- =============================================================
INSERT INTO activities (id, parent_id, category_id, name, slug, description, created_at) VALUES
    (v_act1,  NULL, v_cat_sport_id, 'Football Camerounais',    'football-camerounais',        'Football pratiqué selon les standards FECAFOOT.',                NOW() - INTERVAL '9 days'),
    (v_act2,  NULL, v_cat_sport_id, 'Course a pied tropicale', 'course-pied-tropicale',        'Running adapté au climat chaud et humide d''Afrique centrale.',  NOW() - INTERVAL '9 days'),
    (v_act3,  NULL, v_cat_sport_id, 'Basketball urbain',        'basketball-urbain-sn',         'Basket de rue, format 3x3 ou 5x5 en plein air.',                NOW() - INTERVAL '9 days'),
    (v_act4,  NULL, v_cat_sport_id, 'Natation lacs',            'natation-lacs-cameroun',       'Natation en eaux naturelles supervisée.',                        NOW() - INTERVAL '9 days'),
    (v_act5,  NULL, v_cat_sport_id, 'Musculation fonctionnelle','musculation-fonctionnelle-sn', 'Entrainement au poids de corps et barres en plein air.',         NOW() - INTERVAL '9 days'),
    (v_act6,  NULL, v_cat_sport_id, 'Yoga africain',            'yoga-africain-sn',             'Yoga integrant des mouvements traditionnels africains.',          NOW() - INTERVAL '8 days'),
    (v_act7,  NULL, v_cat_sport_id, 'Boxe technique',           'boxe-technique-sn',            'Entrainement boxe anglaise, accent sur la technique.',            NOW() - INTERVAL '8 days'),
    (v_act8,  NULL, v_cat_sport_id, 'Cyclisme piste',           'cyclisme-piste-sn',            'Cyclisme sur piste et routes de Yaounde.',                        NOW() - INTERVAL '8 days'),
    (v_act9,  NULL, v_cat_sport_id, 'Volley-ball plage',        'volleyball-plage-sn',          'Volley-ball en extérieur sur terrain sablonneux.',               NOW() - INTERVAL '7 days'),
    (v_act10, NULL, v_cat_sport_id, 'Arts martiaux mixtes',     'arts-martiaux-mixtes-sn',      'MMA niveau débutant à intermédiaire, sécurisé.',                 NOW() - INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 2. USER_ACTIVITIES
-- =============================================================
INSERT INTO user_activities (id, user_id, activity_id, visible_on_map, custom_description, level, format, created_at) VALUES
    (v_ua1,  v_user_id, v_act1,  TRUE,  'Je coache des équipes amateurs de football depuis 5 ans.', 'ADVANCED',     'GROUP', NOW() - INTERVAL '9 days'),
    (v_ua2,  v_user_id, v_act2,  TRUE,  'Running matinal, groupes de 5-10 personnes.',              'INTERMEDIATE', 'GROUP', NOW() - INTERVAL '9 days'),
    (v_ua3,  v_user_id, v_act3,  TRUE,  'Basket 3x3 en plein air, accueil tous niveaux.',           'ANY',          'GROUP', NOW() - INTERVAL '9 days'),
    (v_ua4,  v_user_id, v_act4,  FALSE, 'Natation en lac, sécurité prioritaire.',                   'INTERMEDIATE', 'SOLO',  NOW() - INTERVAL '9 days'),
    (v_ua5,  v_user_id, v_act5,  TRUE,  'Circuit training et renforcement musculaire.',              'BEGINNER',     'GROUP', NOW() - INTERVAL '9 days'),
    (v_ua6,  v_user_id, v_act6,  TRUE,  'Séances de yoga en plein air.',                             'BEGINNER',     'ANY',   NOW() - INTERVAL '8 days'),
    (v_ua7,  v_user_id, v_act7,  TRUE,  'Boxe technique sans contact pour débutants.',              'BEGINNER',     'DUO',   NOW() - INTERVAL '8 days'),
    (v_ua8,  v_user_id, v_act8,  FALSE, 'Sorties vélo hebdomadaires.',                               'INTERMEDIATE', 'GROUP', NOW() - INTERVAL '8 days'),
    (v_ua9,  v_user_id, v_act9,  TRUE,  'Volley de plage, mixte, ambiance conviviale.',             'ANY',          'GROUP', NOW() - INTERVAL '7 days'),
    (v_ua10, v_user_id, v_act10, TRUE,  'MMA initiation, sécurité et fun avant tout.',              'BEGINNER',     'DUO',   NOW() - INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 3. PROGRAMS
-- =============================================================
INSERT INTO programs (id, user_activity_id, title, description, status, is_public, organizer_name, organizer_avatar_url, created_at, updated_at)
SELECT
    p.id, p.ua_id, p.title, p.description, p.status, p.is_public,
    u.display_name, u.avatar_url,
    p.created_at, p.updated_at
FROM (VALUES
    (v_prog1,  v_ua1,  'Football Academie Yaounde',   'Programme d''entrainement football niveau amateur, 3 séances/semaine.',          'ACTIVE', TRUE,  NOW() - INTERVAL '8 days', NOW() - INTERVAL '1 day'),
    (v_prog2,  v_ua2,  'Running Matin Cameroun',      'Sorties running matinales en groupe, tous les jours sauf dimanche.',             'ACTIVE', TRUE,  NOW() - INTERVAL '8 days', NOW() - INTERVAL '2 days'),
    (v_prog3,  v_ua3,  'Basket de Rue 3x3',           'Tournois et entrainements basket en plein air, accès libre.',                    'ACTIVE', TRUE,  NOW() - INTERVAL '8 days', NOW() - INTERVAL '1 day'),
    (v_prog4,  v_ua4,  'Natation Lac Municipal',      'Programme de natation en lac, encadrement professionnel inclus.',                'ACTIVE', FALSE, NOW() - INTERVAL '7 days', NOW() - INTERVAL '3 days'),
    (v_prog5,  v_ua5,  'Circuit Training Outdoor',    'Renforcement musculaire fonctionnel en plein air, 45 min par séance.',           'ACTIVE', TRUE,  NOW() - INTERVAL '7 days', NOW() - INTERVAL '2 days'),
    (v_prog6,  v_ua6,  'Yoga Naturel Yaounde',        'Yoga en plein air avec vue sur le Mont Febe, niveaux débutants et confirmés.',   'ACTIVE', TRUE,  NOW() - INTERVAL '6 days', NOW() - INTERVAL '1 day'),
    (v_prog7,  v_ua7,  'Boxe Technique Initiation',   'Cours de boxe anglaise technique, sans sparring pour débutants complets.',       'ACTIVE', TRUE,  NOW() - INTERVAL '6 days', NOW() - INTERVAL '2 days'),
    (v_prog8,  v_ua8,  'Cyclisme Routes de Yaounde',  'Sorties vélo sur routes et pistes, groupes de niveau intermédiaire.',           'DRAFT',  TRUE,  NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day'),
    (v_prog9,  v_ua9,  'Volley Plage Weekend',        'Volley-ball sur terrain extérieur, ambiance détendue, tous les samedis.',        'ACTIVE', TRUE,  NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day'),
    (v_prog10, v_ua10, 'MMA Initiation Cameroun',     'Introduction aux arts martiaux mixtes, accent sur la technique et la sécurité.', 'ACTIVE', TRUE,  NOW() - INTERVAL '4 days', NOW() - INTERVAL '1 day')
) AS p(id, ua_id, title, description, status, is_public, created_at, updated_at)
JOIN users u ON u.id = v_user_id
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 4. SCHEDULES
-- =============================================================
INSERT INTO schedules (id, program_id, place_name, place_type, location, address_public, show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at) VALUES
    (v_sched1,  v_prog1,  'Stade Ahmadou Ahidjo',        'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5102, 3.8700), 4326), 'Avenue Monseigneur Vogt, Yaounde', FALSE, NOW() + INTERVAL '1 day',  NOW() + INTERVAL '1 day 2 hours',             'FREQ=WEEKLY;BYDAY=MO,WE,FR',         20, NOW() - INTERVAL '8 days'),
    (v_sched2,  v_prog2,  'Parc du Mont Febe',           'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5200, 3.8980), 4326), 'Mont Febe, Yaounde',               FALSE, NOW() + INTERVAL '1 day',  NOW() + INTERVAL '1 day 1 hour',              'FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR,SA', 15, NOW() - INTERVAL '8 days'),
    (v_sched3,  v_prog3,  'Terrain Omnisports Essos',    'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5300, 3.8550), 4326), 'Quartier Essos, Yaounde',          TRUE,  NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days 2 hours',            'FREQ=WEEKLY;BYDAY=TU,TH',            18, NOW() - INTERVAL '8 days'),
    (v_sched4,  v_prog4,  'Lac Municipal de Yaounde',    'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5150, 3.8620), 4326), 'Lac Municipal, Yaounde',           FALSE, NOW() + INTERVAL '3 days', NOW() + INTERVAL '3 days 90 minutes',         'FREQ=WEEKLY;BYDAY=SA',               10, NOW() - INTERVAL '7 days'),
    (v_sched5,  v_prog5,  'Parc de la Reunification',   'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5080, 3.8450), 4326), 'Avenue Kennedy, Yaounde',          FALSE, NOW() + INTERVAL '1 day',  NOW() + INTERVAL '1 day 45 minutes',          'FREQ=WEEKLY;BYDAY=MO,WE,FR',         25, NOW() - INTERVAL '7 days'),
    (v_sched6,  v_prog6,  'Jardin Botanique de Yaounde', 'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5010, 3.8500), 4326), 'Jardin Botanique, Yaounde',        FALSE, NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days 90 minutes',         'FREQ=WEEKLY;BYDAY=TU,TH,SA',         12, NOW() - INTERVAL '6 days'),
    (v_sched7,  v_prog7,  'Salle Polyvalente Omnisports','PRIVATE', ST_SetSRID(ST_MakePoint(11.5180, 3.8610), 4326), 'Complexe Omnisports, Yaounde',     TRUE,  NOW() + INTERVAL '1 day',  NOW() + INTERVAL '1 day 90 minutes',          'FREQ=WEEKLY;BYDAY=MO,WE',            16, NOW() - INTERVAL '6 days'),
    (v_sched8,  v_prog8,  'Circuit Collines de Yaounde', 'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5250, 3.8750), 4326), 'Point de depart: Nlongkak',        FALSE, NOW() + INTERVAL '4 days', NOW() + INTERVAL '4 days 3 hours',            'FREQ=WEEKLY;BYDAY=SU',                8, NOW() - INTERVAL '5 days'),
    (v_sched9,  v_prog9,  'Beach-Volley Club Yaounde',   'PUBLIC',  ST_SetSRID(ST_MakePoint(11.5060, 3.8430), 4326), 'Quartier Bastos, Yaounde',         FALSE, NOW() + INTERVAL '5 days', NOW() + INTERVAL '5 days 2 hours',            'FREQ=WEEKLY;BYDAY=SA',               16, NOW() - INTERVAL '5 days'),
    (v_sched10, v_prog10, 'Dojo Centre Sportif',         'PRIVATE', ST_SetSRID(ST_MakePoint(11.5130, 3.8560), 4326), 'Centre Sportif, Yaounde',          TRUE,  NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days 90 minutes',         'FREQ=WEEKLY;BYDAY=TU,FR',            14, NOW() - INTERVAL '4 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 5. PROGRAM_MEDIA
-- =============================================================
INSERT INTO program_media (id, program_id, url, media_type, sort_order, created_at) VALUES
    (v_med1,  v_prog1,  'https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=800', 'IMAGE', 0, NOW() - INTERVAL '8 days'),
    (v_med2,  v_prog2,  'https://images.unsplash.com/photo-1502904550040-7534597429ae?w=800', 'IMAGE', 0, NOW() - INTERVAL '8 days'),
    (v_med3,  v_prog3,  'https://images.unsplash.com/photo-1546519638405-a2a9a489a8b4?w=800', 'IMAGE', 0, NOW() - INTERVAL '8 days'),
    (v_med4,  v_prog4,  'https://images.unsplash.com/photo-1560090995-01632a28895b?w=800', 'IMAGE', 0, NOW() - INTERVAL '7 days'),
    (v_med5,  v_prog5,  'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800', 'IMAGE', 0, NOW() - INTERVAL '7 days'),
    (v_med6,  v_prog6,  'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800', 'IMAGE', 0, NOW() - INTERVAL '6 days'),
    (v_med7,  v_prog7,  'https://images.unsplash.com/photo-1549719386-74dfcbf7dbed?w=800', 'IMAGE', 0, NOW() - INTERVAL '6 days'),
    (v_med8,  v_prog8,  'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800', 'IMAGE', 0, NOW() - INTERVAL '5 days'),
    (v_med9,  v_prog9,  'https://images.unsplash.com/photo-1612872087720-bb876e2e67d1?w=800', 'IMAGE', 0, NOW() - INTERVAL '5 days'),
    (v_med10, v_prog10, 'https://images.unsplash.com/photo-1555597673-b21d5c935865?w=800', 'IMAGE', 0, NOW() - INTERVAL '4 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 6. USER_PROGRAMS
-- =============================================================
INSERT INTO user_programs (id, user_id, program_id, schedule_id, status, progress_percentage, activities_completed, activities_skipped, last_activity_at, joined_at) VALUES
    (v_up1,  v_user_id, v_prog1,  v_sched1,  'ACTIVE',  100, 12, 0, NOW() - INTERVAL '1 day',  NOW() - INTERVAL '8 days'),
    (v_up2,  v_user_id, v_prog2,  v_sched2,  'ACTIVE',   80, 16, 2, NOW() - INTERVAL '1 day',  NOW() - INTERVAL '8 days'),
    (v_up3,  v_user_id, v_prog3,  v_sched3,  'ACTIVE',   60,  6, 1, NOW() - INTERVAL '2 days', NOW() - INTERVAL '7 days'),
    (v_up4,  v_user_id, v_prog4,  v_sched4,  'ACTIVE',   40,  2, 0, NOW() - INTERVAL '3 days', NOW() - INTERVAL '6 days'),
    (v_up5,  v_user_id, v_prog5,  v_sched5,  'ACTIVE',   90,  9, 1, NOW() - INTERVAL '1 day',  NOW() - INTERVAL '7 days'),
    (v_up6,  v_user_id, v_prog6,  v_sched6,  'ACTIVE',   50,  5, 0, NOW() - INTERVAL '2 days', NOW() - INTERVAL '6 days'),
    (v_up7,  v_user_id, v_prog7,  v_sched7,  'ACTIVE',   70,  7, 1, NOW() - INTERVAL '1 day',  NOW() - INTERVAL '5 days'),
    (v_up8,  v_user_id, v_prog8,  v_sched8,  'PAUSED',   30,  3, 2, NOW() - INTERVAL '4 days', NOW() - INTERVAL '5 days'),
    (v_up9,  v_user_id, v_prog9,  v_sched9,  'ACTIVE',   85,  8, 1, NOW() - INTERVAL '1 day',  NOW() - INTERVAL '4 days'),
    (v_up10, v_user_id, v_prog10, v_sched10, 'ACTIVE',   20,  2, 0, NOW() - INTERVAL '2 days', NOW() - INTERVAL '3 days')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 7. PROGRAM_ACTIVITIES
-- =============================================================
INSERT INTO program_activities (id, user_program_id, activity_id, status, completed_at, skipped_at, notes) VALUES
    (v_pa1,  v_up1,  v_act1,  'COMPLETED', NOW() - INTERVAL '1 day',  NULL,                      'Session d''entrainement tactique réussie.'),
    (v_pa2,  v_up2,  v_act2,  'COMPLETED', NOW() - INTERVAL '1 day',  NULL,                      '8 km en 45 min, bon rythme.'),
    (v_pa3,  v_up3,  v_act3,  'COMPLETED', NOW() - INTERVAL '2 days', NULL,                      'Match 3x3 très disputé, victoire 15-12.'),
    (v_pa4,  v_up4,  v_act4,  'PENDING',   NULL,                       NULL,                      NULL),
    (v_pa5,  v_up5,  v_act5,  'COMPLETED', NOW() - INTERVAL '1 day',  NULL,                      'Circuit complet 45 min, tous les exercices maîtrisés.'),
    (v_pa6,  v_up6,  v_act6,  'COMPLETED', NOW() - INTERVAL '2 days', NULL,                      'Séance yoga très relaxante.'),
    (v_pa7,  v_up7,  v_act7,  'COMPLETED', NOW() - INTERVAL '1 day',  NULL,                      'Bonne progression sur les combinaisons.'),
    (v_pa8,  v_up8,  v_act8,  'SKIPPED',   NULL,                       NOW() - INTERVAL '4 days', 'Meteo défavorable.'),
    (v_pa9,  v_up9,  v_act9,  'COMPLETED', NOW() - INTERVAL '1 day',  NULL,                      'Tournoi volley gagné 2-1.'),
    (v_pa10, v_up10, v_act10, 'PENDING',   NULL,                       NULL,                      NULL)
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 8. CONVERSATIONS (preuve d'interaction pour les reviews)
-- =============================================================
INSERT INTO conversations (id, type, created_at, last_message_at) VALUES
    (v_conv1,  'DIRECT', NOW() - INTERVAL '7 days', NOW() - INTERVAL '3 days'),
    (v_conv2,  'DIRECT', NOW() - INTERVAL '7 days', NOW() - INTERVAL '3 days'),
    (v_conv3,  'DIRECT', NOW() - INTERVAL '6 days', NOW() - INTERVAL '2 days'),
    (v_conv4,  'DIRECT', NOW() - INTERVAL '6 days', NOW() - INTERVAL '2 days'),
    (v_conv5,  'DIRECT', NOW() - INTERVAL '5 days', NOW() - INTERVAL '2 days'),
    (v_conv6,  'DIRECT', NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day'),
    (v_conv7,  'DIRECT', NOW() - INTERVAL '4 days', NOW() - INTERVAL '1 day'),
    (v_conv8,  'DIRECT', NOW() - INTERVAL '4 days', NOW() - INTERVAL '1 day'),
    (v_conv9,  'DIRECT', NOW() - INTERVAL '3 days', NOW() - INTERVAL '12 hours'),
    (v_conv10, 'DIRECT', NOW() - INTERVAL '3 days', NOW() - INTERVAL '12 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO conversation_members (conversation_id, user_id, joined_at) VALUES
    (v_conv1,  v_user_id, NOW() - INTERVAL '7 days'),
    (v_conv1,  v_alice,   NOW() - INTERVAL '7 days'),
    (v_conv2,  v_user_id, NOW() - INTERVAL '7 days'),
    (v_conv2,  v_bob,     NOW() - INTERVAL '7 days'),
    (v_conv3,  v_user_id, NOW() - INTERVAL '6 days'),
    (v_conv3,  v_claire,  NOW() - INTERVAL '6 days'),
    (v_conv4,  v_user_id, NOW() - INTERVAL '6 days'),
    (v_conv4,  v_david,   NOW() - INTERVAL '6 days'),
    (v_conv5,  v_user_id, NOW() - INTERVAL '5 days'),
    (v_conv5,  v_emma,    NOW() - INTERVAL '5 days'),
    (v_conv6,  v_user_id, NOW() - INTERVAL '5 days'),
    (v_conv6,  v_alice,   NOW() - INTERVAL '5 days'),
    (v_conv7,  v_user_id, NOW() - INTERVAL '4 days'),
    (v_conv7,  v_bob,     NOW() - INTERVAL '4 days'),
    (v_conv8,  v_user_id, NOW() - INTERVAL '4 days'),
    (v_conv8,  v_claire,  NOW() - INTERVAL '4 days'),
    (v_conv9,  v_user_id, NOW() - INTERVAL '3 days'),
    (v_conv9,  v_david,   NOW() - INTERVAL '3 days'),
    (v_conv10, v_user_id, NOW() - INTERVAL '3 days'),
    (v_conv10, v_emma,    NOW() - INTERVAL '3 days')
ON CONFLICT DO NOTHING;

-- =============================================================
-- 9. REVIEWS
-- =============================================================
INSERT INTO reviews (id, program_id, reviewer_id, interaction_proof_id, score, comment, created_at) VALUES
    (v_rev1,  v_prog1,  v_alice,  v_conv1,  5.0, 'Excellent coach, très professionnel et motivant !',             NOW() - INTERVAL '3 days'),
    (v_rev2,  v_prog2,  v_bob,    v_conv2,  4.5, 'Super programme de running, bien adapté au climat local.',      NOW() - INTERVAL '3 days'),
    (v_rev3,  v_prog3,  v_claire, v_conv3,  4.0, 'Ambiance sympa, bon niveau basket. Je recommande.',            NOW() - INTERVAL '2 days'),
    (v_rev4,  v_prog4,  v_david,  v_conv4,  4.5, 'Natation très bien encadrée, sécurité au top.',                NOW() - INTERVAL '2 days'),
    (v_rev5,  v_prog5,  v_emma,   v_conv5,  5.0, 'Circuit training parfait pour se remettre en forme.',          NOW() - INTERVAL '2 days'),
    (v_rev6,  v_prog6,  v_alice,  v_conv6,  4.0, 'Yoga en plein air magnifique. Cadre exceptionnel.',            NOW() - INTERVAL '1 day'),
    (v_rev7,  v_prog7,  v_bob,    v_conv7,  4.5, 'Très bon cours de boxe, pédagogie claire et progressive.',    NOW() - INTERVAL '1 day'),
    (v_rev8,  v_prog8,  v_claire, v_conv8,  3.5, 'Programme cyclisme pas encore terminé mais prometteur.',       NOW() - INTERVAL '1 day'),
    (v_rev9,  v_prog9,  v_david,  v_conv9,  4.0, 'Volley plage du samedi, la meilleure façon de finir la semaine.', NOW() - INTERVAL '12 hours'),
    (v_rev10, v_prog10, v_emma,   v_conv10, 4.5, 'MMA initiation très bien expliqué, sans danger, très fun.',   NOW() - INTERVAL '12 hours')
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 10. REVIEW_CRITERIA
-- =============================================================
INSERT INTO review_criteria (id, review_id, criterion_key, score) VALUES
    (v_rc1,  v_rev1,  'coaching_quality',   5.0),
    (v_rc2,  v_rev2,  'program_structure',  4.5),
    (v_rc3,  v_rev3,  'atmosphere',         4.0),
    (v_rc4,  v_rev4,  'safety',             5.0),
    (v_rc5,  v_rev5,  'physical_results',   5.0),
    (v_rc6,  v_rev6,  'environment',        4.0),
    (v_rc7,  v_rev7,  'technique_teaching', 4.5),
    (v_rc8,  v_rev8,  'content_quality',    3.5),
    (v_rc9,  v_rev9,  'fun_factor',         4.0),
    (v_rc10, v_rev10, 'safety',             4.5)
ON CONFLICT (id) DO NOTHING;

-- =============================================================
-- 11. PROGRESSION_ENTRIES
-- =============================================================
INSERT INTO progression_entries (id, program_id, user_id, title, content, metrics, is_public, created_at) VALUES
    (v_pe1,  v_prog1,  v_user_id, 'Semaine 1 Football',     'Bonne cohésion d''équipe, travail défensif à améliorer.',   ARRAY[7.5, 8.0, 6.5],    TRUE,  NOW() - INTERVAL '7 days'),
    (v_pe2,  v_prog2,  v_user_id, 'Course J1',              'Objectif 5 km atteint en 28 min. Très satisfait !',        ARRAY[28.0, 5.0, 145.0], TRUE,  NOW() - INTERVAL '7 days'),
    (v_pe3,  v_prog3,  v_user_id, 'Match Basket 1',         'Victoire 21-14, bonne défense de zone.',                   ARRAY[21.0, 14.0, 8.0],  TRUE,  NOW() - INTERVAL '6 days'),
    (v_pe4,  v_prog4,  v_user_id, 'Natation Lap 1',         'Premier 500m en 12 min, technique crawl à perfectionner.', ARRAY[500.0, 12.0, 3.0], FALSE, NOW() - INTERVAL '6 days'),
    (v_pe5,  v_prog5,  v_user_id, 'Circuit J1',             '3 séries complètes, 10 répétitions chaque exercice.',      ARRAY[3.0, 10.0, 45.0],  TRUE,  NOW() - INTERVAL '6 days'),
    (v_pe6,  v_prog6,  v_user_id, 'Yoga Session 1',         'Postures de base maîtrisées, respiration bien contrôlée.', ARRAY[6.0, 8.5, 9.0],    TRUE,  NOW() - INTERVAL '5 days'),
    (v_pe7,  v_prog7,  v_user_id, 'Boxe Seance 1',          'Enchainements jab-direct acquis, garde à améliorer.',      ARRAY[4.0, 3.0, 7.5],    TRUE,  NOW() - INTERVAL '5 days'),
    (v_pe8,  v_prog8,  v_user_id, 'Velo Sortie 1',          '25 km en 1h15, terrain vallonné. Bonne sortie.',           ARRAY[25.0, 75.0, 320.0],FALSE, NOW() - INTERVAL '4 days'),
    (v_pe9,  v_prog9,  v_user_id, 'Volley Tournament S1',   'Win set 25-18, service efficace, réceptions à travailler.',ARRAY[25.0, 18.0, 7.0],  TRUE,  NOW() - INTERVAL '3 days'),
    (v_pe10, v_prog10, v_user_id, 'MMA J1 Postures base',   'Garde et déplacements basiques assimilés, bonne séance.',  ARRAY[5.0, 8.0, 9.0],    TRUE,  NOW() - INTERVAL '2 days')
ON CONFLICT (id) DO NOTHING;

RAISE NOTICE 'Seed pour seyd.njoya@icloud.com terminé avec succès.';
END $$;
