-- icon: Google Material Icons ligature name (e.g. "sports_soccer").
-- Falls back to "sports" if no specific icon has been assigned.
-- IF NOT EXISTS: the column already exists in environments where V3 defined it.
ALTER TABLE activities
    ADD COLUMN IF NOT EXISTS icon VARCHAR(80) DEFAULT 'sports' NOT NULL;
