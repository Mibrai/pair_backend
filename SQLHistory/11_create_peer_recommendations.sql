-- ==================================================
-- Phase 3 Module 2: Peer Recommendations
-- ==================================================
-- Date: 2026-06-23
-- Description: Système de recommandations entre pairs
-- Règle: Une recommandation nécessite une conversation existante (proof of interaction)

CREATE TABLE IF NOT EXISTS peer_recommendations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who recommends whom
    recommender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recommended_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Proof of interaction (must have chatted)
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,

    -- Recommendation details
    comment TEXT NOT NULL CHECK (char_length(comment) >= 20 AND char_length(comment) <= 500),
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),

    -- Context
    activity_context UUID REFERENCES activities(id) ON DELETE SET NULL,
    program_context UUID REFERENCES programs(id) ON DELETE SET NULL,

    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT no_self_recommendation CHECK (recommender_id != recommended_id),
    CONSTRAINT unique_recommendation UNIQUE (recommender_id, recommended_id)
);

-- Indexes
CREATE INDEX idx_peer_recommendations_recommender ON peer_recommendations(recommender_id);
CREATE INDEX idx_peer_recommendations_recommended ON peer_recommendations(recommended_id);
CREATE INDEX idx_peer_recommendations_conversation ON peer_recommendations(conversation_id);
CREATE INDEX idx_peer_recommendations_activity ON peer_recommendations(activity_context);
CREATE INDEX idx_peer_recommendations_program ON peer_recommendations(program_context);
CREATE INDEX idx_peer_recommendations_rating ON peer_recommendations(rating);
CREATE INDEX idx_peer_recommendations_created ON peer_recommendations(created_at DESC);

-- Trigger for updated_at
CREATE TRIGGER update_peer_recommendations_updated_at
BEFORE UPDATE ON peer_recommendations
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- ==================================================
-- Vue: Statistiques des recommandations par utilisateur
-- ==================================================
CREATE OR REPLACE VIEW user_recommendation_stats AS
SELECT
    u.id AS user_id,
    COUNT(DISTINCT pr.id) AS recommendations_received_count,
    COALESCE(AVG(pr.rating), 0) AS average_rating,
    COUNT(DISTINCT pr.recommender_id) AS unique_recommenders,
    MAX(pr.created_at) AS last_recommendation_at
FROM users u
LEFT JOIN peer_recommendations pr ON pr.recommended_id = u.id
GROUP BY u.id;

-- ==================================================
-- Résumé:
-- - Table peer_recommendations avec contraintes strictes
-- - Preuve d'interaction requise (conversation_id)
-- - Pas d'auto-recommandation
-- - Une seule recommandation par paire (unique)
-- - Rating 1-5 obligatoire
-- - Comment 20-500 caractères
-- - Context optionnel (activity, program)
-- - Vue stats pour agrégation
-- ==================================================

COMMIT;
