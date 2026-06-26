-- Script pour ajouter la colonne location à la table users existante
-- À exécuter APRÈS avoir créé l'extension PostGIS

-- D'abord, vérifier que PostGIS est installé
SELECT PostGIS_Version();

-- Ajouter la colonne location si elle n'existe pas
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'users'
        AND column_name = 'location'
    ) THEN
        ALTER TABLE users ADD COLUMN location GEOMETRY(Point, 4326);
    END IF;
END $$;

-- Créer l'index spatial
DROP INDEX IF EXISTS idx_users_location;
CREATE INDEX idx_users_location ON users USING GIST(location);

-- Vérifier que la colonne a été ajoutée
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'users'
ORDER BY ordinal_position;
