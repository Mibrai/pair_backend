DO $$
DECLARE
  u1  UUID := 'aaaaaaaa-0000-0000-0000-000000000001';
  u2  UUID := 'aaaaaaaa-0000-0000-0000-000000000002';
  u3  UUID := 'aaaaaaaa-0000-0000-0000-000000000003';
  u4  UUID := 'aaaaaaaa-0000-0000-0000-000000000004';
  u5  UUID := 'aaaaaaaa-0000-0000-0000-000000000005';
  users UUID[];

  i INT; j INT; k INT;
  v_user_id UUID;
  cur_id     UUID;
  cur_ua_id  UUID;
  cur_conv   UUID;
  ua_count   INT;
  rec        RECORD;

  pair_as UUID[];
  pair_bs UUID[];

  alice_titles  TEXT[];
  bob_titles    TEXT[];
  claire_titles TEXT[];
  david_titles  TEXT[];
  emma_titles   TEXT[];

BEGIN
  users    := ARRAY[u1,u2,u3,u4,u5];
  pair_as  := ARRAY[u1,u1,u1,u1, u2,u2,u2, u3,u3, u4];
  pair_bs  := ARRAY[u2,u3,u4,u5, u3,u4,u5, u4,u5, u5];

  alice_titles  := ARRAY['Running débutant matin','Interval Training 5K','Running endurance 10K',
    'Préparation semi-marathon','Running vitesse Vincennes','Trail Meudon découverte',
    'Running et yoga matinal','Fartlek entraînement','Running récupération active',
    'Course en côte Belleville','Running technique biomécanique','Running nocturne Paris',
    'Running group challenge','Marathon préparation 16S','Running et renforcement',
    'Session 10km chrono','Running mindful','Coaching running individuel',
    'Jogging Parc Montsouris','Running bien-être total'];
  bob_titles    := ARRAY['Football 5x5 lundi','Football 5x5 mercredi','Entraînement tirs au but',
    'Football technique passes','Football gardiens','Football tactique défense',
    'Football attaque combinaisons','Match mixte tous niveaux','Football jeunes adultes',
    'Préparation tournoi','Rondo petits espaces','Football endurance',
    'Football haute intensité','Football transitions','Futsal débutant',
    'Football freestyle','Analyse vidéo tactique','Match 11vs11 samedi',
    'Football toutes positions','Stage football weekend'];
  claire_titles := ARRAY['Yoga Hatha débutant','Yoga Vinyasa flow','Yoga restauratif soir',
    'Yoga power avancé','Méditation pleine conscience','Yoga nidra',
    'Yoga et pranayama','Yoga dos et posture','Yoga flexibilité',
    'Méditation mantra','Yoga ashtanga matin','Yoga barre cardio',
    'Yin yoga profond','Yoga lunch break','Yoga anti-stress',
    'Méditation visualisation','Yoga en plein air','Yoga detox',
    'Yoga sons bols','Yoga et aromathérapie'];
  david_titles  := ARRAY['Vélo route débutant 30km','VTT forêt Meudon','Endurance longue 80km',
    'Cyclisme côtes entraînement','Sortie dominicale 50km','Vélo gravel adventure',
    'Sprint cyclisme','Sortie nocturne Paris','Vélo et café social',
    'Préparation cyclosportive','VTT technique descente','Endurance basse intensité',
    'Bikepacking initiation','Triathlon vélo section','Rando vélo vintage',
    'Cyclisme récupération','Défi 100km','Vélo cross-training',
    'Photo et vélo Paris','Vélo bien-être'];
  emma_titles   := ARRAY['Natation crawl technique','Natation endurance 2000m','Natation débutant adultes',
    'Dos crawlé perfectionnement','Brasse technique','Papillon initiation',
    'Natation 4 nages','Prépa compétition','Natation open water',
    'Aquarunning cardio','Triathlon section natation','Natation récupération',
    'Eau libre lac Daumesnil','Natation 1500m chrono','Aqua-fitness',
    'Natation relâchement','Virages culbutes','Natation synchronisée adultes',
    'Natation masters 40+','Nage avec palmes'];

  -- =========================================================
  -- TEMP TABLES
  -- =========================================================
  CREATE TEMP TABLE _uas   (u_idx INT, seq INT, ua_id UUID, v_user_id UUID)    ON COMMIT DROP;
  CREATE TEMP TABLE _progs (u_idx INT, seq INT, prog_id UUID, v_user_id UUID)  ON COMMIT DROP;
  CREATE TEMP TABLE _convs (pair_k INT, conv_j INT, conv_id UUID, ua UUID, ub UUID) ON COMMIT DROP;

  -- =========================================================
  -- 1. NEW ACTIVITIES (20)
  -- =========================================================
  INSERT INTO activities(id, parent_id, category_id, name, slug, description, embedding, created_at) VALUES
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000001','Tennis',            'tennis',            'Sport de raquette sur court.',                  NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000001','Badminton',         'badminton',         'Sport de volant en salle.',                     NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000001','Handball',          'handball',          'Sport collectif 7 joueurs en salle.',            NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000001','Rugby',             'rugby',             'Sport collectif au ballon ovale.',               NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000002','CrossFit',          'crossfit',          'Entraînement fonctionnel haute intensité.',      NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000002','Musculation',       'musculation',       'Développement musculaire en salle.',             NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000002','Triathlon',         'triathlon',         'Natation, vélo et course à pied enchaînés.',    NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000002','Squash',            'squash',            'Sport de raquette en espace fermé.',             NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000003','Pilates',           'pilates',           'Méthode gainage corps-esprit.',                  NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000003','Stretching',        'stretching',        'Étirements et souplesse corporelle.',            NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000003','Tai-chi',           'tai-chi',           'Art martial chinois méditatif.',                 NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000003','Danse',             'danse-contemporaine','Danse contemporaine expressive.',              NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000004','Boxe',              'boxe',              'Sport de combat à poings.',                      NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000004','Escalade',          'escalade',          'Grimpe sur mur artificiel ou rocher.',           NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000004','Taekwondo',         'taekwondo',         'Art martial coréen pieds et poings.',            NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000004','Boxe thaïlandaise', 'boxe-thai',         'Muay Thai complet.',                             NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000005','Surf',              'surf',              'Sport de glisse sur les vagues.',                NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000005','Paddle',            'paddle',            'Stand-up paddleboard.',                          NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000005','Ski alpin',         'ski-alpin',         'Descente sur neige avec skis.',                  NULL,NOW()),
  (gen_random_uuid(),NULL,'bbbbbbbb-0000-0000-0000-000000000005','Aviron',            'aviron',            'Sport nautique en embarcation.',                 NULL,NOW())
  ON CONFLICT (slug) DO NOTHING;

  -- =========================================================
  -- 2. USER_ACTIVITIES (up to 20 per user)
  -- =========================================================
  FOR i IN 1..5 LOOP
    v_user_id := users[i];
    j   := 0;
    FOR rec IN
      SELECT id
      FROM   activities
      WHERE  id NOT IN (SELECT activity_id FROM user_activities WHERE user_id = v_user_id)
      ORDER  BY created_at
      LIMIT  GREATEST(0, 20 - (SELECT COUNT(*) FROM user_activities WHERE user_id = v_user_id)::INT)
    LOOP
      cur_id := gen_random_uuid();
      INSERT INTO user_activities(id, user_id, activity_id, visible_on_map, level, format, created_at)
      VALUES(cur_id, v_user_id, rec.id, j%3 != 2,
        CASE j%4 WHEN 0 THEN 'BEGINNER' WHEN 1 THEN 'INTERMEDIATE' WHEN 2 THEN 'ADVANCED' ELSE 'ANY' END,
        CASE j%3 WHEN 0 THEN 'SOLO' WHEN 1 THEN 'DUO' ELSE 'GROUP' END,
        NOW() - ((j*3 + i*2) || ' days')::INTERVAL);
      INSERT INTO _uas VALUES(i, j, cur_id, v_user_id);
      j := j + 1;
    END LOOP;
    -- Store existing user_activities too (for program distribution)
    FOR rec IN SELECT id FROM user_activities WHERE user_id = v_user_id AND id NOT IN (SELECT ua_id FROM _uas) ORDER BY created_at LOOP
      INSERT INTO _uas VALUES(i, j + 100, rec.id, v_user_id);
      j := j + 1;
    END LOOP;
  END LOOP;

  -- =========================================================
  -- 3. PROGRAMS (20 per user)
  -- =========================================================
  FOR i IN 1..5 LOOP
    v_user_id := users[i];
    SELECT COUNT(*) INTO ua_count FROM _uas WHERE u_idx = i;
    FOR j IN 0..19 LOOP
      cur_id := gen_random_uuid();
      SELECT ua_id INTO cur_ua_id FROM _uas WHERE u_idx = i ORDER BY seq LIMIT 1 OFFSET (j % ua_count);
      INSERT INTO programs(id, user_activity_id, title, description, embedding, status, is_public, created_at, updated_at)
      VALUES(cur_id, cur_ua_id,
        CASE i
          WHEN 1 THEN alice_titles[j+1]
          WHEN 2 THEN bob_titles[j+1]
          WHEN 3 THEN claire_titles[j+1]
          WHEN 4 THEN david_titles[j+1]
          ELSE        emma_titles[j+1]
        END,
        'Programme animé par ' || (ARRAY['Alice','Bob','Claire','David','Emma'])[i] ||
        '. ' || (ARRAY['Tous niveaux bienvenus.','Places limitées, inscrivez-vous !',
                       'Ambiance bienveillante garantie.','Programme personnalisé.',
                       'Petits groupes pour mieux progresser.'])[j%5+1],
        NULL,
        CASE WHEN j < 17 THEN 'PUBLISHED' ELSE 'DRAFT' END,
        j < 17,
        NOW() - ((40 - j*2) || ' days')::INTERVAL,
        NOW() - (j || ' hours')::INTERVAL);
      INSERT INTO _progs VALUES(i, j, cur_id, v_user_id);
    END LOOP;
  END LOOP;

  -- =========================================================
  -- 4. SCHEDULES (2 per program = 200 total)
  -- =========================================================
  INSERT INTO schedules(id, program_id, place_name, place_type, location, address_public,
                        show_exact_address, starts_at, ends_at, recurrence_rule, max_participants, created_at)
  SELECT
    gen_random_uuid(), p.prog_id,
    (ARRAY['Champ de Mars','Bois de Vincennes','Jardin des Tuileries','Parc Montsouris',
           'Bois de Boulogne','Parc des Buttes-Chaumont','Piscine Molitor','Piscine Pontoise',
           'Studio Zen Marais','Gymnase Jean Bouin','Stade Charlety','Terrain Javel'])
      [((p.u_idx * 3 + p.seq + s.n) % 12) + 1],
    CASE ((p.seq + s.n) % 3) WHEN 0 THEN 'OUTDOOR' ELSE 'INDOOR' END,
    ST_SetSRID(ST_MakePoint(
      2.24 + (p.seq * 0.013 + s.n * 0.025 + p.u_idx * 0.018),
      48.83 + (p.u_idx * 0.009 + p.seq * 0.005 + s.n * 0.007)
    ), 4326),
    NULL, FALSE,
    NOW() + ((p.seq % 14 + 1 + s.n * 7) || ' days')::INTERVAL
          + ((p.u_idx * 2 + s.n)         || ' hours')::INTERVAL,
    NOW() + ((p.seq % 14 + 1 + s.n * 7) || ' days')::INTERVAL
          + ((p.u_idx * 2 + s.n + 2)     || ' hours')::INTERVAL,
    (ARRAY['FREQ=WEEKLY;BYDAY=SA','FREQ=WEEKLY;BYDAY=SU','FREQ=WEEKLY;BYDAY=MO,WE',
           'FREQ=WEEKLY;BYDAY=TU,TH','FREQ=WEEKLY;BYDAY=FR'])[((p.seq + s.n) % 5) + 1],
    CASE p.u_idx WHEN 1 THEN 10 WHEN 2 THEN 10 WHEN 3 THEN 8 WHEN 4 THEN 15 ELSE 8 END,
    NOW() - ((40 - p.seq * 2) || ' days')::INTERVAL
  FROM _progs p
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1) s;

  -- =========================================================
  -- 5. PROGRAM MEDIA (1 per program)
  -- =========================================================
  INSERT INTO program_media(id, program_id, url, media_type, sort_order, created_at)
  SELECT
    gen_random_uuid(), prog_id,
    (ARRAY[
      'https://images.unsplash.com/photo-1476480862126-209bfaa8edc8?w=800',
      'https://images.unsplash.com/photo-1571008887538-b36bb32f4571?w=800',
      'https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=800',
      'https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=800',
      'https://images.unsplash.com/photo-1530549387789-4c1017266635?w=800',
      'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800',
      'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800',
      'https://images.unsplash.com/photo-1581009137042-c552e485697a?w=800',
      'https://images.unsplash.com/photo-1571019613454-1cb2f99b2d8b?w=800',
      'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800'
    ])[((u_idx * 4 + seq) % 10) + 1],
    'IMAGE', 0,
    NOW() - ((40 - seq * 2) || ' days')::INTERVAL
  FROM _progs;

  -- =========================================================
  -- 6. CONVERSATIONS (5 per user pair = 50 total)
  -- =========================================================
  FOR k IN 1..10 LOOP
    FOR j IN 1..5 LOOP
      cur_id := gen_random_uuid();
      INSERT INTO conversations(id, type, activity_context_id, created_at, last_message_at)
      VALUES(cur_id, 'DIRECT', NULL,
        NOW() - ((50 - k*5 - j)     || ' days')::INTERVAL,
        NOW() - ((j*2 + k % 6)      || ' hours')::INTERVAL);
      INSERT INTO conversation_members(conversation_id, user_id, joined_at, last_read_at) VALUES
        (cur_id, pair_as[k], NOW() - ((50-k*5-j) || ' days')::INTERVAL, NOW() - ((j*2+k%6+1) || ' hours')::INTERVAL),
        (cur_id, pair_bs[k], NOW() - ((50-k*5-j) || ' days')::INTERVAL, NOW() - ((j*2+k%6+2) || ' hours')::INTERVAL);
      INSERT INTO _convs VALUES(k, j, cur_id, pair_as[k], pair_bs[k]);
    END LOOP;
  END LOOP;

  -- =========================================================
  -- 7. MESSAGES (10 per conversation = 500 total)
  -- =========================================================
  INSERT INTO messages(id, conversation_id, sender_id, content, status, sent_at, read_at)
  SELECT
    gen_random_uuid(), c.conv_id,
    CASE (n % 2) WHEN 0 THEN c.ua ELSE c.ub END,
    (ARRAY[
      'Salut ! Disponible pour une session bientôt ?',
      'Oui bien sûr, quand tu veux !',
      'Super, on fait quoi cette semaine ?',
      'Je pensais au même endroit qu''habituellement.',
      'Parfait. Quelle heure te convient le mieux ?',
      'Le matin c''est idéal pour moi.',
      'OK, je note. Tu as besoin de quelque chose ?',
      'Non, j''ai tout. Hâte d''y être !',
      'Moi aussi, ce sera une belle session.',
      'À demain alors, bonne soirée !',
      'Merci pour aujourd''hui, c''était top !',
      'Avec plaisir ! Tu progresses vraiment bien.',
      'On refait ça la semaine prochaine ?',
      'Absolument, j''ai déjà hâte.',
      'Confirme-moi ça demain matin.',
      'Pas de souci, à très bientôt !',
      'Tu connais un bon spot pour s''entraîner ?',
      'Oui, j''ai l''endroit parfait en tête.',
      'Super, envoie-moi l''adresse.',
      'Je te fais ça ce soir, bonne soirée !'
    ])[(n % 20) + 1],
    CASE WHEN n <= 7 THEN 'READ' ELSE 'SENT' END,
    NOW() - ((11 - n) || ' hours')::INTERVAL,
    CASE WHEN n <= 7 THEN NOW() - ((10 - n) || ' hours')::INTERVAL ELSE NULL END
  FROM _convs c
  CROSS JOIN generate_series(1, 10) AS n;

  -- =========================================================
  -- 8. REVIEWS (up to 20 per user on others' programs)
  -- =========================================================
  FOR i IN 1..5 LOOP
    v_user_id := users[i];
    j   := 0;
    FOR rec IN
      SELECT p.prog_id, p.v_user_id AS owner_id
      FROM   _progs p
      WHERE  p.u_idx != i
        AND  NOT EXISTS (SELECT 1 FROM reviews r WHERE r.program_id = p.prog_id AND r.reviewer_id = v_user_id)
      ORDER  BY p.u_idx, p.seq
      LIMIT  20
    LOOP
      -- Find a conversation between reviewer and program owner
      SELECT c.conv_id INTO cur_conv FROM _convs c
      WHERE (c.ua = v_user_id AND c.ub = rec.owner_id) OR (c.ub = v_user_id AND c.ua = rec.owner_id)
      LIMIT 1;
      -- Fallback
      IF cur_conv IS NULL THEN
        SELECT conv_id INTO cur_conv FROM _convs LIMIT 1;
      END IF;

      cur_id := gen_random_uuid();
      INSERT INTO reviews(id, program_id, reviewer_id, interaction_proof_id, score, comment, created_at)
      VALUES(cur_id, rec.prog_id, v_user_id, cur_conv,
        (3.0 + (j % 5) * 0.5)::FLOAT,
        (ARRAY[
          'Excellent programme, très professionnel !',
          'J''ai adoré cette session, je reviendrai.',
          'Coach très à l''écoute et compétent.',
          'Session intense et bien structurée.',
          'Superbe ambiance, groupe sympa.',
          'Programme complet, j''ai beaucoup appris.',
          'Très satisfait, je recommande vivement.',
          'Bonne expérience, je reviendrai !'
        ])[j % 8 + 1],
        NOW() - ((20 - j) || ' days')::INTERVAL);

      INSERT INTO review_criteria(id, review_id, criterion_key, score) VALUES
        (gen_random_uuid(), cur_id, 'ponctualite',  (3.5 + (j%3)*0.5)::FLOAT),
        (gen_random_uuid(), cur_id, 'pedagogie',    (3.5 + (j%4)*0.4)::FLOAT),
        (gen_random_uuid(), cur_id, 'ambiance',     (4.0 + (j%3)*0.3)::FLOAT);

      j := j + 1;
    END LOOP;
  END LOOP;

  -- =========================================================
  -- 9. NOTIFICATIONS (20 per user)
  -- =========================================================
  INSERT INTO notifications(id, user_id, type, channel, payload, is_read, sent_at, read_at)
  SELECT
    gen_random_uuid(), u.uid_val,
    (ARRAY['NEW_MESSAGE','NEW_REVIEW','PROGRAM_REMINDER','NEW_BADGE',
           'NEW_MESSAGE','NEW_PEER_REC','MATCH_FOUND','PROGRAM_REMINDER',
           'NEW_REVIEW','NEW_MESSAGE','PROGRAM_CANCELLED','SCHEDULE_CHANGED',
           'NEW_BADGE','MATCH_FOUND','NEW_MESSAGE','NEW_REVIEW',
           'PROGRAM_REMINDER','NEW_PEER_REC','NEW_MESSAGE','SYSTEM'])[n],
    CASE (n%3) WHEN 0 THEN 'PUSH' WHEN 1 THEN 'IN_APP' ELSE 'EMAIL' END,
    ('{"index":' || n || ',"user":"' || u.uname || '"}')::JSONB,
    n <= 14,
    NOW() - ((21 - n) || ' hours')::INTERVAL,
    CASE WHEN n <= 14 THEN NOW() - ((20 - n) || ' hours')::INTERVAL ELSE NULL END
  FROM (VALUES (u1,'Alice'),(u2,'Bob'),(u3,'Claire'),(u4,'David'),(u5,'Emma')) AS u(uid_val, uname)
  CROSS JOIN generate_series(1, 20) AS n;

  -- =========================================================
  -- 10. NOTIFICATION PREFS (10 types per user)
  -- =========================================================
  INSERT INTO notification_prefs(id, user_id, notification_type, email_enabled, push_enabled, frequency)
  SELECT
    gen_random_uuid(), uid_val, t.ntype,
    (t.n % 3 != 2), TRUE,
    CASE (t.n % 3) WHEN 0 THEN 'IMMEDIATE' WHEN 1 THEN 'DAILY' ELSE 'WEEKLY' END
  FROM UNNEST(users) AS uid_val
  CROSS JOIN (VALUES
    (1,'NEW_MESSAGE'),(2,'NEW_REVIEW'),(3,'PROGRAM_REMINDER'),(4,'NEW_BADGE'),
    (5,'NEW_FOLLOWER'),(6,'NEW_PEER_REC'),(7,'PROGRAM_CANCELLED'),
    (8,'SCHEDULE_CHANGED'),(9,'MATCH_FOUND'),(10,'SYSTEM')
  ) AS t(n, ntype)
  WHERE NOT EXISTS (
    SELECT 1 FROM notification_prefs np WHERE np.user_id = uid_val AND np.notification_type = t.ntype
  );

  -- =========================================================
  -- 11. PROGRESSION ENTRIES (1 per program = 20 per user)
  -- =========================================================
  INSERT INTO progression_entries(id, program_id, user_id, title, content, metrics, is_public, created_at)
  SELECT
    gen_random_uuid(), prog_id, v_user_id,
    'Session ' || (seq + 1)::TEXT,
    (ARRAY[
      'Belle séance aujourd''hui, progression notable !',
      'Session difficile mais très formatrice.',
      'Nouveau record personnel, je suis ravi !',
      'Superbe ambiance dans le groupe.',
      'J''ai enfin maîtrisé ce mouvement.',
      'Encore des efforts à fournir, bonne direction.',
      'Très satisfait de cette sortie.',
      'On progresse semaine après semaine !'
    ])[seq % 8 + 1],
    ARRAY[(seq + 1)::FLOAT, (30 + seq * 3)::FLOAT],
    seq % 4 != 3,
    NOW() - ((40 - seq * 2) || ' days')::INTERVAL
  FROM _progs;

  -- =========================================================
  -- 12. SEARCH LOGS (20 per user)
  -- =========================================================
  INSERT INTO search_logs(id, user_id, raw_query, parsed_intent, query_embedding, results_count, created_at)
  SELECT
    gen_random_uuid(), uid_val,
    (ARRAY['running paris','yoga matin','football 5x5','vélo bois de boulogne',
           'natation endurance','crossfit paris','tennis débutant','pilates cours',
           'randonnée nature','méditation pleine conscience','boxe paris','escalade intérieure',
           'triathlon paris','stretching souplesse','arts martiaux','sport collectif paris',
           'badminton salle','danse contemporaine','ski alpin week-end','sport bien-être'])[n],
    ('{"n":' || n || '}')::JSONB,
    NULL,
    (n * 3) % 15 + 1,
    NOW() - ((21 - n) || ' days')::INTERVAL
  FROM UNNEST(users) AS uid_val
  CROSS JOIN generate_series(1, 20) AS n;

  -- =========================================================
  -- 13. REPORTS (4 per user on others' programs)
  -- =========================================================
  FOR i IN 1..5 LOOP
    v_user_id := users[i];
    j   := 0;
    FOR rec IN
      SELECT prog_id FROM _progs WHERE u_idx != i ORDER BY u_idx, seq LIMIT 20
    LOOP
      IF NOT EXISTS (SELECT 1 FROM reports WHERE reporter_id = v_user_id AND target_id = rec.prog_id) THEN
        INSERT INTO reports(id, reporter_id, target_type, target_id, reason, status, created_at, resolved_at)
        VALUES(gen_random_uuid(), v_user_id, 'PROGRAM', rec.prog_id,
          (ARRAY['SPAM','INAPPROPRIATE_CONTENT','MISLEADING_INFORMATION','HARASSMENT'])[j % 4 + 1],
          CASE j%3 WHEN 0 THEN 'OPEN' WHEN 1 THEN 'RESOLVED' ELSE 'DISMISSED' END,
          NOW() - ((20 - j) || ' days')::INTERVAL,
          CASE j%3 WHEN 0 THEN NULL ELSE NOW() - ((10 - j%5) || ' days')::INTERVAL END);
        j := j + 1;
      END IF;
      EXIT WHEN j >= 20;
    END LOOP;
  END LOOP;

  -- =========================================================
  -- 14. PEER RECOMMENDATIONS (all unique directed pairs)
  -- =========================================================
  INSERT INTO peer_recommendations(id, from_user_id, to_user_id, interaction_proof_id, comment, created_at)
  SELECT DISTINCT ON (c.ua, c.ub)
    gen_random_uuid(), c.ua, c.ub, c.conv_id,
    'Partenaire sérieux et très motivant, je recommande sans hésiter !',
    NOW() - (c.pair_k || ' days')::INTERVAL
  FROM _convs c
  WHERE NOT EXISTS (SELECT 1 FROM peer_recommendations pr WHERE pr.from_user_id = c.ua AND pr.to_user_id = c.ub)
  ORDER BY c.ua, c.ub;

  INSERT INTO peer_recommendations(id, from_user_id, to_user_id, interaction_proof_id, comment, created_at)
  SELECT DISTINCT ON (c.ub, c.ua)
    gen_random_uuid(), c.ub, c.ua, c.conv_id,
    'Excellente personne, très agréable à pratiquer ensemble !',
    NOW() - ((c.pair_k + 1) || ' days')::INTERVAL
  FROM _convs c
  WHERE NOT EXISTS (SELECT 1 FROM peer_recommendations pr WHERE pr.from_user_id = c.ub AND pr.to_user_id = c.ua)
  ORDER BY c.ub, c.ua;

  -- =========================================================
  -- 15. DEVICE TOKENS (2 extra per user)
  -- =========================================================
  INSERT INTO device_tokens(id, user_id, token, platform, device_name, created_at, last_used_at)
  SELECT
    gen_random_uuid(), u.uid_val,
    'fcm_' || u.uname || '_v2_device' || n::TEXT || '_' || LEFT(MD5(u.uid_val::TEXT || n::TEXT), 16),
    CASE n WHEN 1 THEN 'IOS' ELSE 'ANDROID' END,
    CASE n WHEN 1 THEN 'iPad Pro' ELSE 'Samsung Tab' END,
    NOW() - ((25 - n * 5) || ' days')::INTERVAL,
    NOW() - (n || ' hours')::INTERVAL
  FROM (VALUES (u1,'alice'),(u2,'bob'),(u3,'claire'),(u4,'david'),(u5,'emma')) AS u(uid_val, uname)
  CROSS JOIN generate_series(1, 2) AS n;

END $$;
