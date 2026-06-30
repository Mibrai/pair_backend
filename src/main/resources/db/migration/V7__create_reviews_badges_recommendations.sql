-- Create reviews table
CREATE TABLE IF NOT EXISTS reviews (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id              UUID NOT NULL,
    reviewer_id             UUID NOT NULL,
    interaction_proof_id    UUID NOT NULL,
    score                   FLOAT NOT NULL CHECK (score >= 1 AND score <= 5),
    comment                 VARCHAR(1000),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_reviews_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_interaction FOREIGN KEY (interaction_proof_id) REFERENCES conversations(id) ON DELETE RESTRICT,
    CONSTRAINT uq_review_program_reviewer UNIQUE (program_id, reviewer_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_program ON reviews(program_id);
CREATE INDEX IF NOT EXISTS idx_reviews_reviewer ON reviews(reviewer_id);

-- Create review_criteria table
CREATE TABLE IF NOT EXISTS review_criteria (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id       UUID NOT NULL,
    criterion_key   VARCHAR(30) NOT NULL,
    score           FLOAT NOT NULL CHECK (score >= 1 AND score <= 5),
    CONSTRAINT fk_review_criteria_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE
);

-- Create peer_recommendations table
CREATE TABLE IF NOT EXISTS peer_recommendations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user_id            UUID NOT NULL,
    to_user_id              UUID NOT NULL,
    interaction_proof_id    UUID NOT NULL,
    comment                 VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_peer_rec_from FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_peer_rec_to FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_peer_rec_interaction FOREIGN KEY (interaction_proof_id) REFERENCES conversations(id) ON DELETE RESTRICT,
    CONSTRAINT uq_peer_rec_from_to UNIQUE (from_user_id, to_user_id)
);

CREATE INDEX IF NOT EXISTS idx_peer_rec_to ON peer_recommendations(to_user_id);
CREATE INDEX IF NOT EXISTS idx_peer_rec_from ON peer_recommendations(from_user_id);

-- Create badges table
CREATE TABLE IF NOT EXISTS badges (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(60) NOT NULL UNIQUE,
    category            VARCHAR(20) NOT NULL,
    label               VARCHAR(120) NOT NULL,
    condition_type      VARCHAR(40) NOT NULL,
    condition_threshold INTEGER,
    icon                VARCHAR(80)
);

-- Create badge_awards table
CREATE TABLE IF NOT EXISTS badge_awards (
    badge_id    UUID NOT NULL,
    user_id     UUID NOT NULL,
    awarded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (badge_id, user_id),
    CONSTRAINT fk_badge_awards_badge FOREIGN KEY (badge_id) REFERENCES badges(id) ON DELETE CASCADE,
    CONSTRAINT fk_badge_awards_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
