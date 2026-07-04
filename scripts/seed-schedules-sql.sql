-- =============================================================================
-- seed-schedules-sql.sql
-- 1. Crée des programmes pour seyd.njoya@icloud.com (si < 3 existants)
-- 2. Ajoute 2 schedules (lieux allemands) à chaque programme sans schedule
--
-- Prérequis : extension PostGIS activée
-- Usage Railway CLI :
--   railway run psql $DATABASE_URL -f scripts/seed-schedules-sql.sql
-- =============================================================================

BEGIN;

-- =============================================================================
-- PARTIE 1 — Création des programmes (idempotent, max 3)
-- =============================================================================

INSERT INTO programs (id, user_activity_id, title, description, status, is_public, created_at)
SELECT
    gen_random_uuid(),
    ua.id,
    'Programme Natation Débutants',
    'Programme de 8 semaines pour apprendre les bases de la natation : crawl, dos crawlé, brasse.',
    'ACTIVE',
    true,
    NOW()
FROM user_activities ua
JOIN users u ON u.id = ua.user_id
WHERE u.email = 'seyd.njoya@icloud.com'
  AND NOT EXISTS (
      SELECT 1
      FROM   programs p3
      JOIN   user_activities ua3 ON ua3.id = p3.user_activity_id
      WHERE  ua3.user_id = ua.user_id
        AND  p3.title = 'Programme Natation Débutants'
  )
  AND (
      SELECT COUNT(*)
      FROM   programs p2
      JOIN   user_activities ua2 ON ua2.id = p2.user_activity_id
      WHERE  ua2.user_id = ua.user_id
        AND  p2.status != 'ARCHIVED'
  ) < 3
LIMIT 1;

INSERT INTO programs (id, user_activity_id, title, description, status, is_public, created_at)
SELECT
    gen_random_uuid(),
    ua.id,
    'Circuit Running Matinal',
    'Sorties running en groupe, 5 à 10 km selon le niveau. Départ tous les matins à 6h30 du parc.',
    'ACTIVE',
    true,
    NOW()
FROM user_activities ua
JOIN users u ON u.id = ua.user_id
WHERE u.email = 'seyd.njoya@icloud.com'
  AND NOT EXISTS (
      SELECT 1
      FROM   programs p3
      JOIN   user_activities ua3 ON ua3.id = p3.user_activity_id
      WHERE  ua3.user_id = ua.user_id
        AND  p3.title = 'Circuit Running Matinal'
  )
  AND (
      SELECT COUNT(*)
      FROM   programs p2
      JOIN   user_activities ua2 ON ua2.id = p2.user_activity_id
      WHERE  ua2.user_id = ua.user_id
        AND  p2.status != 'ARCHIVED'
  ) < 3
LIMIT 1;

INSERT INTO programs (id, user_activity_id, title, description, status, is_public, created_at)
SELECT
    gen_random_uuid(),
    ua.id,
    'Football Loisir Samedi',
    'Matches de football amicaux chaque samedi matin. Format 7v7, terrains en herbe synthetique.',
    'ACTIVE',
    true,
    NOW()
FROM user_activities ua
JOIN users u ON u.id = ua.user_id
WHERE u.email = 'seyd.njoya@icloud.com'
  AND NOT EXISTS (
      SELECT 1
      FROM   programs p3
      JOIN   user_activities ua3 ON ua3.id = p3.user_activity_id
      WHERE  ua3.user_id = ua.user_id
        AND  p3.title = 'Football Loisir Samedi'
  )
  AND (
      SELECT COUNT(*)
      FROM   programs p2
      JOIN   user_activities ua2 ON ua2.id = p2.user_activity_id
      WHERE  ua2.user_id = ua.user_id
        AND  p2.status != 'ARCHIVED'
  ) < 3
LIMIT 1;

-- =============================================================================
-- PARTIE 2 — Ajout des schedules (2 par programme, lieux allemands)
-- Round-robin : lieu_idx = (prog_rank * 2 + slot) % 8
-- =============================================================================

WITH lieux (idx, place_name, place_type, lat, lng, address_public, starts_offset, duration_h, max_p) AS (
    VALUES
        (0, 'Olympiastadion Berlin',      'PUBLIC', 52.5147::float8, 13.2394::float8, 'Olympischer Platz 3, 14053 Berlin',             7::int, 2::int, 20::int),
        (1, 'Englischer Garten Munchen',  'PUBLIC', 48.1642::float8, 11.6050::float8, 'Englischer Garten 1, 80538 Munchen',            8,      2,      15),
        (2, 'Stadtpark Hamburg',          'PUBLIC', 53.5924::float8, 10.0024::float8, 'Am Stadtpark 1, 22299 Hamburg',                 9,      1,      25),
        (3, 'Rheinpark Koln',             'PUBLIC', 50.9658::float8,  6.9808::float8, 'Rheinparkweg 1, 51063 Koln',                   10,      2,      30),
        (4, 'Palmengarten Frankfurt',     'PUBLIC', 50.1236::float8,  8.6568::float8, 'Siesmayerstrasse 61, 60323 Frankfurt am Main',  11,      2,      20),
        (5, 'Eilenriede Hannover',        'PUBLIC', 52.3805::float8,  9.7785::float8, 'Eilenriede, 30161 Hannover',                   12,      2,      22),
        (6, 'Buergerpark Bremen',         'PUBLIC', 53.0924::float8,  8.8203::float8, 'Marcusallee 1, 28359 Bremen',                  14,      2,      25),
        (7, 'Westfalenpark Dortmund',     'PUBLIC', 51.4987::float8,  7.4889::float8, 'Westfalenpark 1, 44139 Dortmund',              15,      2,      18)
),
programs_ranked AS (
    SELECT
        p.id                                                   AS prog_id,
        (ROW_NUMBER() OVER (ORDER BY p.created_at) - 1)::int  AS prog_rank
    FROM programs p
    WHERE p.status != 'ARCHIVED'
),
sched_counts AS (
    SELECT program_id, COUNT(*)::int AS cnt
    FROM   schedules
    GROUP  BY program_id
),
slots AS (
    SELECT
        pr.prog_id,
        pr.prog_rank,
        s.slot::int AS slot
    FROM      programs_ranked pr
    CROSS JOIN generate_series(0, 1) AS s(slot)
    LEFT  JOIN sched_counts sc ON sc.program_id = pr.prog_id
    WHERE  s.slot >= COALESCE(sc.cnt, 0)
)
INSERT INTO schedules (
    id, program_id, place_name, place_type, location,
    address_public, show_exact_address,
    starts_at, ends_at, max_participants, created_at
)
SELECT
    gen_random_uuid(),
    sl.prog_id,
    l.place_name,
    l.place_type,
    ST_SetSRID(ST_MakePoint(l.lng, l.lat), 4326),
    l.address_public,
    true,
    NOW() + make_interval(days => l.starts_offset + sl.slot * 7),
    NOW() + make_interval(days => l.starts_offset + sl.slot * 7, hours => l.duration_h),
    l.max_p,
    NOW()
FROM  slots sl
JOIN  lieux l ON l.idx = (sl.prog_rank * 2 + sl.slot) % 8;

-- =============================================================================
-- Vérification finale
-- =============================================================================
SELECT
    p.title                                                        AS programme,
    p.status,
    COUNT(s.id)                                                    AS nb_schedules,
    STRING_AGG(s.place_name, ', ' ORDER BY s.starts_at)           AS lieux,
    STRING_AGG(
        round(ST_Y(s.location::geometry)::numeric, 4)::text
        || ', '
        || round(ST_X(s.location::geometry)::numeric, 4)::text,
        ' | ' ORDER BY s.starts_at
    )                                                              AS "lat, lng"
FROM   programs p
LEFT   JOIN schedules s ON s.program_id = p.id
WHERE  p.status != 'ARCHIVED'
GROUP  BY p.id, p.title, p.status, p.created_at
ORDER  BY p.created_at;

COMMIT;
