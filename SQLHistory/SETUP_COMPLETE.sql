-- =================================================================
-- SQLHistory - Complete Database Setup
-- =================================================================
-- Execute this file to create all tables and seed initial data
-- Database: pair_db
-- User: pair_user
-- Password: Pair2026!
--
-- Run with:
-- set PGPASSWORD=Pair2026!
-- psql -h localhost -U pair_user -d pair_db -f SETUP_COMPLETE.sql
-- =================================================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

\echo '=== Creating Categories Table ==='
CREATE TABLE IF NOT EXISTS categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(80) NOT NULL UNIQUE,
    icon        VARCHAR(80),
    color_ramp  VARCHAR(30)
);

\echo '=== Creating Activities Table ==='
DROP TABLE IF EXISTS user_activities CASCADE;
DROP TABLE IF EXISTS activities CASCADE;

CREATE TABLE activities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID,
    category_id UUID NOT NULL,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(150) NOT NULL UNIQUE,
    description VARCHAR(500),
    embedding   vector(1536),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_activities_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT
);

ALTER TABLE activities
ADD CONSTRAINT fk_activities_parent FOREIGN KEY (parent_id) REFERENCES activities(id) ON DELETE CASCADE;

CREATE INDEX idx_activities_slug ON activities(slug);
CREATE INDEX idx_activities_category ON activities(category_id);

\echo '=== Creating User Activities Table ==='
CREATE TABLE user_activities (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    activity_id         UUID NOT NULL,
    visible_on_map      BOOLEAN NOT NULL DEFAULT TRUE,
    custom_description  VARCHAR(500),
    level               VARCHAR(20) DEFAULT 'ANY',
    format              VARCHAR(10) DEFAULT 'ANY',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ua_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ua_activity FOREIGN KEY (activity_id) REFERENCES activities(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_activity UNIQUE (user_id, activity_id)
);

CREATE INDEX idx_ua_user ON user_activities(user_id);
CREATE INDEX idx_ua_activity ON user_activities(activity_id);
CREATE INDEX idx_ua_visible ON user_activities(visible_on_map);

\echo '=== Seeding Categories ==='
INSERT INTO categories (id, name, icon, color_ramp) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Sport', '⚽', 'blue'),
    ('22222222-2222-2222-2222-222222222222', 'Musique', '🎵', 'purple'),
    ('33333333-3333-3333-3333-333333333333', 'Art', '🎨', 'pink'),
    ('44444444-4444-4444-4444-444444444444', 'Jeux', '🎮', 'green')
ON CONFLICT (id) DO NOTHING;

\echo '=== Seeding Activities - Sport ==='
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'Tennis', 'tennis', 'Sport de raquette'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'Football', 'football', 'Sport collectif avec un ballon'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'Running', 'running', 'Course à pied'),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111', 'Yoga', 'yoga', 'Pratique corporelle et spirituelle'),
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111', 'Basketball', 'basketball', 'Sport collectif avec un panier')
ON CONFLICT (slug) DO NOTHING;

\echo '=== Seeding Activities - Music ==='
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222', 'Guitare', 'guitare', 'Instrument à cordes'),
    ('gggggggg-gggg-gggg-gggg-gggggggggggg', '22222222-2222-2222-2222-222222222222', 'Piano', 'piano', 'Instrument à clavier'),
    ('hhhhhhhh-hhhh-hhhh-hhhh-hhhhhhhhhhhh', '22222222-2222-2222-2222-222222222222', 'Chant', 'chant', 'Art vocal')
ON CONFLICT (slug) DO NOTHING;

\echo '=== Seeding Activities - Art ==='
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('iiiiiiii-iiii-iiii-iiii-iiiiiiiiiiii', '33333333-3333-3333-3333-333333333333', 'Peinture', 'peinture', 'Art visuel'),
    ('jjjjjjjj-jjjj-jjjj-jjjj-jjjjjjjjjjjj', '33333333-3333-3333-3333-333333333333', 'Photographie', 'photographie', 'Art de capturer des images')
ON CONFLICT (slug) DO NOTHING;

\echo '=== Seeding Activities - Games ==='
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('kkkkkkkk-kkkk-kkkk-kkkk-kkkkkkkkkkkk', '44444444-4444-4444-4444-444444444444', 'Échecs', 'echecs', 'Jeu de stratégie'),
    ('llllllll-llll-llll-llll-llllllllllll', '44444444-4444-4444-4444-444444444444', 'Poker', 'poker', 'Jeu de cartes')
ON CONFLICT (slug) DO NOTHING;

\echo ''
\echo '=== Setup Complete! ==='
\echo 'Tables created:'
\echo '  - categories (4 rows)'
\echo '  - activities (12 rows)'
\echo '  - user_activities'
\echo ''
