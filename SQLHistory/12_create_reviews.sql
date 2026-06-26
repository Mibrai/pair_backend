-- ==================================================
-- Phase 3 Module 3: Program Reviews
-- ==================================================
-- Date: 2026-06-23
-- Description: Système d'avis sur les programmes
-- Règle: Un avis nécessite une conversation existante (proof of interaction)

-- Create enum for review criteria
CREATE TYPE review_criterion AS ENUM (
    'ORGANIZATION',
    'COMMUNICATION',
    'ATMOSPHERE',
    'DIFFICULTY',
    'RECOMMENDATION'
);

CREATE TABLE IF NOT EXISTS reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who reviews what
    reviewer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    program_id UUID NOT NULL REFERENCES programs(id) ON DELETE CASCADE,

    -- Proof of interaction (must have chatted with program creator)
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,

    -- Overall rating
    overall_rating INTEGER NOT NULL CHECK (overall_rating >= 1 AND overall_rating <= 5),

    -- Criteria scores (JSONB for flexibility)
    criteria_scores JSONB NOT NULL,
    -- Expected structure:
    -- {
    --   "ORGANIZATION": 4,
    --   "COMMUNICATION": 5,
    --   "ATMOSPHERE": 5,
    --   "DIFFICULTY": 3,
    --   "RECOMMENDATION": 4
    -- }

    -- Comment
    comment TEXT NOT NULL CHECK (char_length(comment) >= 30 AND char_length(comment) <= 1000),

    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT unique_review UNIQUE (reviewer_id, program_id)
);

-- Indexes
CREATE INDEX idx_reviews_reviewer ON reviews(reviewer_id);
CREATE INDEX idx_reviews_program ON reviews(program_id);
CREATE INDEX idx_reviews_conversation ON reviews(conversation_id);
CREATE INDEX idx_reviews_rating ON reviews(overall_rating);
CREATE INDEX idx_reviews_created ON reviews(created_at DESC);
CREATE INDEX idx_reviews_criteria_gin ON reviews USING gin(criteria_scores);

-- Trigger for updated_at
CREATE TRIGGER update_reviews_updated_at
BEFORE UPDATE ON reviews
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- ==================================================
-- Vue: Statistiques des avis par programme
-- ==================================================
CREATE OR REPLACE VIEW program_review_stats AS
SELECT
    p.id AS program_id,
    COUNT(DISTINCT r.id) AS review_count,
    COALESCE(AVG(r.overall_rating), 0) AS average_rating,
    COALESCE(AVG((r.criteria_scores->>'ORGANIZATION')::INTEGER), 0) AS avg_organization,
    COALESCE(AVG((r.criteria_scores->>'COMMUNICATION')::INTEGER), 0) AS avg_communication,
    COALESCE(AVG((r.criteria_scores->>'ATMOSPHERE')::INTEGER), 0) AS avg_atmosphere,
    COALESCE(AVG((r.criteria_scores->>'DIFFICULTY')::INTEGER), 0) AS avg_difficulty,
    COALESCE(AVG((r.criteria_scores->>'RECOMMENDATION')::INTEGER), 0) AS avg_recommendation,
    MAX(r.created_at) AS last_review_at
FROM programs p
LEFT JOIN reviews r ON r.program_id = p.id
GROUP BY p.id;

-- ==================================================
-- Fonction: Mettre à jour average_score et review_count du programme
-- ==================================================
CREATE OR REPLACE FUNCTION update_program_review_stats()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE programs
    SET
        average_score = (
            SELECT COALESCE(AVG(overall_rating), 0)
            FROM reviews
            WHERE program_id = COALESCE(NEW.program_id, OLD.program_id)
        ),
        review_count = (
            SELECT COUNT(*)
            FROM reviews
            WHERE program_id = COALESCE(NEW.program_id, OLD.program_id)
        )
    WHERE id = COALESCE(NEW.program_id, OLD.program_id);

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Trigger pour maintenir les stats à jour
CREATE TRIGGER trigger_update_program_review_stats
AFTER INSERT OR UPDATE OR DELETE ON reviews
FOR EACH ROW
EXECUTE FUNCTION update_program_review_stats();

-- ==================================================
-- Résumé:
-- - Table reviews avec contraintes strictes
-- - Preuve d'interaction requise (conversation_id)
-- - Une seule review par utilisateur/programme
-- - Overall rating 1-5 obligatoire
-- - Criteria scores en JSONB (5 critères)
-- - Comment 30-1000 caractères
-- - Vue stats pour agrégation
-- - Trigger auto-update de program.average_score et review_count
-- ==================================================

COMMIT;
