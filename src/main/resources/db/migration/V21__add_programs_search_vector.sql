-- Add full-text search vector to programs table
ALTER TABLE programs ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- Populate from existing data
UPDATE programs SET search_vector = to_tsvector('french', coalesce(title,'') || ' ' || coalesce(description,''))
WHERE search_vector IS NULL;

-- Index for fast full-text search
CREATE INDEX IF NOT EXISTS idx_programs_search_vector ON programs USING GIN(search_vector);

-- Trigger to auto-update on insert/update
CREATE OR REPLACE FUNCTION programs_search_vector_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_vector = to_tsvector('french', coalesce(NEW.title,'') || ' ' || coalesce(NEW.description,''));
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS programs_search_vector_trig ON programs;
CREATE TRIGGER programs_search_vector_trig
    BEFORE INSERT OR UPDATE ON programs
    FOR EACH ROW EXECUTE FUNCTION programs_search_vector_update();
