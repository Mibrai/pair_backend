-- Test complet des fonctionnalités géographiques de Pair

-- 1. Insérer des utilisateurs avec des positions géographiques
UPDATE users
SET location = ST_SetSRID(ST_MakePoint(2.3522, 48.8566), 4326)  -- Paris
WHERE email = 'admin@pair.com';

UPDATE users
SET location = ST_SetSRID(ST_MakePoint(2.2945, 48.8584), 4326)  -- Arc de Triomphe
WHERE email = 'geo-test@example.com';

-- 2. Vérifier que les positions sont bien enregistrées
SELECT
    email,
    display_name,
    ST_AsText(location) AS location_text,
    ST_X(location) AS longitude,
    ST_Y(location) AS latitude
FROM users
WHERE location IS NOT NULL;

-- 3. Calculer la distance entre deux utilisateurs (en mètres)
SELECT
    u1.email AS user1,
    u2.email AS user2,
    ROUND(ST_Distance(
        u1.location::geography,
        u2.location::geography
    )) AS distance_meters
FROM users u1, users u2
WHERE u1.email = 'admin@pair.com'
    AND u2.email = 'geo-test@example.com'
    AND u1.location IS NOT NULL
    AND u2.location IS NOT NULL;

-- 4. Trouver tous les utilisateurs dans un rayon de 5km autour de Paris
SELECT
    email,
    display_name,
    ROUND(ST_Distance(
        location::geography,
        ST_SetSRID(ST_MakePoint(2.3522, 48.8566), 4326)::geography
    )) AS distance_from_paris_meters
FROM users
WHERE location IS NOT NULL
    AND ST_DWithin(
        location::geography,
        ST_SetSRID(ST_MakePoint(2.3522, 48.8566), 4326)::geography,
        5000  -- 5km
    )
ORDER BY distance_from_paris_meters;

-- 5. Appliquer le floutage (blur) - simuler un déplacement aléatoire
-- Ceci simule ce que fera l'application pour protéger la vie privée
SELECT
    email,
    ST_AsText(location) AS position_exacte,
    ST_AsText(
        ST_Project(
            location::geography,
            blur_radius_m * random(),  -- distance aléatoire dans le rayon
            radians(360 * random())     -- angle aléatoire
        )::geometry
    ) AS position_floutee,
    blur_radius_m
FROM users
WHERE location IS NOT NULL
LIMIT 3;

-- 6. Résumé
SELECT
    COUNT(*) AS total_users,
    COUNT(location) AS users_with_location,
    COUNT(*) - COUNT(location) AS users_without_location
FROM users;
