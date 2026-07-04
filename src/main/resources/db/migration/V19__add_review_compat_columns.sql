-- Add backwards-compatible columns to reviews for API compatibility
-- These columns allow both old and new code to work against the same schema
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS overall_rating INTEGER;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS criteria_scores JSONB;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS conversation_id UUID;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- Populate from existing data
UPDATE reviews SET overall_rating = ROUND(score) WHERE overall_rating IS NULL AND score IS NOT NULL;
UPDATE reviews SET conversation_id = interaction_proof_id WHERE conversation_id IS NULL;
