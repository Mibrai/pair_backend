-- Script de test pour vérifier PostGIS

-- 1. Vérifier si l'extension PostGIS est installée
SELECT
    extname AS "Extension",
    extversion AS "Version"
FROM pg_extension
WHERE extname IN ('postgis', 'postgis_topology');

-- 2. Vérifier la version de PostGIS
SELECT PostGIS_Version() AS "PostGIS Version";

-- 3. Vérifier les fonctions PostGIS disponibles
SELECT PostGIS_Full_Version() AS "PostGIS Full Info";

-- 4. Test de création d'un point géographique
SELECT ST_AsText(ST_SetSRID(ST_MakePoint(2.3522, 48.8566), 4326)) AS "Paris Coordinates (lon, lat)";

-- 5. Vérifier si la colonne location existe dans users
SELECT
    table_name,
    column_name,
    data_type,
    udt_name
FROM information_schema.columns
WHERE table_name = 'users'
    AND column_name = 'location';

-- 6. Vérifier les index spatiaux
SELECT
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'users'
    AND indexname LIKE '%location%';

-- 7. Test simple : insérer et lire un point
-- (Ne pas exécuter si vous voulez garder les données propres)
/*
DO $$
BEGIN
    -- Vérifier si on peut insérer un point
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'location'
    ) THEN
        -- Test d'insertion simple
        INSERT INTO users (email, password_hash, display_name, location)
        VALUES (
            'test_postgis@example.com',
            '$2a$12$test',
            'PostGIS Test User',
            ST_SetSRID(ST_MakePoint(2.3522, 48.8566), 4326)
        )
        ON CONFLICT (email) DO NOTHING;

        -- Lire le point
        RAISE NOTICE 'Point géographique inséré avec succès!';
    ELSE
        RAISE NOTICE 'La colonne location n''existe pas encore';
    END IF;
END $$;
*/
