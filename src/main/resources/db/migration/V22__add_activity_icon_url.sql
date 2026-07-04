-- icon: Google Material Icons ligature name (e.g. "sports_soccer").
-- Falls back to "sports" if no specific icon has been assigned.
ALTER TABLE activities
    ADD COLUMN icon VARCHAR(80) DEFAULT 'sports' NOT NULL;
