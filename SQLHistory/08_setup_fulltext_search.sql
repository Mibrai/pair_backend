-- =================================================================
-- Setup Full-Text Search (alternative à pgvector)
-- Phase 2: Recherche intelligente
-- =================================================================

\echo '=== Setting up Full-Text Search for Activities ==='

-- Add tsvector column for activities
ALTER TABLE activities
ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- Create GIN index for fast text search
CREATE INDEX IF NOT EXISTS idx_activities_search
ON activities USING gin(search_vector);

-- Update existing rows
UPDATE activities
SET search_vector =
    setweight(to_tsvector('french', COALESCE(name, '')), 'A') ||
    setweight(to_tsvector('french', COALESCE(description, '')), 'B');

-- Create trigger for automatic updates
CREATE OR REPLACE FUNCTION activities_search_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('french', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('french', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS activities_search_update ON activities;
CREATE TRIGGER activities_search_update
BEFORE INSERT OR UPDATE ON activities
FOR EACH ROW EXECUTE FUNCTION activities_search_trigger();

\echo ''
\echo '=== Setting up Full-Text Search for Programs ==='

-- Add tsvector column for programs
ALTER TABLE programs
ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- Create GIN index
CREATE INDEX IF NOT EXISTS idx_programs_search
ON programs USING gin(search_vector);

-- Update existing rows (will join with user_activities and activities)
UPDATE programs p
SET search_vector =
    setweight(to_tsvector('french', COALESCE(p.title, '')), 'A') ||
    setweight(to_tsvector('french', COALESCE(p.description, '')), 'B');

-- Create trigger for automatic updates
CREATE OR REPLACE FUNCTION programs_search_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('french', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('french', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS programs_search_update ON programs;
CREATE TRIGGER programs_search_update
BEFORE INSERT OR UPDATE ON programs
FOR EACH ROW EXECUTE FUNCTION programs_search_trigger();

\echo ''
\echo '=== Creating Search Logs Table ==='

CREATE TABLE IF NOT EXISTS search_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    raw_query TEXT NOT NULL,
    parsed_intent JSONB,
    search_method VARCHAR(50) DEFAULT 'fulltext',
    results_count INT,
    searched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_search_log_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_search_logs_user ON search_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_search_logs_date ON search_logs(searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_logs_method ON search_logs(search_method);

\echo ''
\echo '=== Testing Full-Text Search ==='

-- Test search on activities
SELECT
    name,
    description,
    ts_rank(search_vector, to_tsquery('french', 'tennis')) as rank
FROM activities
WHERE search_vector @@ to_tsquery('french', 'tennis')
ORDER BY rank DESC
LIMIT 3;

-- Test search on programs
SELECT
    title,
    description,
    ts_rank(search_vector, to_tsquery('french', 'sport')) as rank
FROM programs
WHERE search_vector @@ to_tsquery('french', 'sport')
ORDER BY rank DESC
LIMIT 3;

\echo ''
\echo '=== Full-Text Search Setup Complete! ==='
\echo 'Features:'
\echo '  - tsvector columns on activities and programs'
\echo '  - GIN indexes for fast search'
\echo '  - Automatic updates via triggers'
\echo '  - French language stemming'
\echo '  - Weighted search (title=A, description=B)'
\echo '  - search_logs table for analytics'
\echo ''
\echo 'Note: When pgvector is available, migration will be simple'
