-- image_url: representative photo for the activity (shown in activity lists/cards).
-- Nullable like programs.image_url — activities created afterwards may not have one yet.
ALTER TABLE activities ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);
