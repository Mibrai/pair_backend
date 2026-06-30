-- Create reports table
CREATE TABLE IF NOT EXISTS reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   UUID NOT NULL,
    reason      VARCHAR(30) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reports_reporter ON reports(reporter_id);
CREATE INDEX IF NOT EXISTS idx_reports_target ON reports(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status);

-- Create search_logs table
CREATE TABLE IF NOT EXISTS search_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID,
    raw_query       VARCHAR(500) NOT NULL,
    parsed_intent   JSONB,
    query_embedding vector(1536),
    results_count   INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_search_logs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_search_log_user ON search_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_search_log_created ON search_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_search_logs_embedding ON search_logs USING hnsw (query_embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- Create progression_entries table
CREATE TABLE IF NOT EXISTS progression_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id  UUID NOT NULL,
    user_id     UUID NOT NULL,
    title       VARCHAR(150),
    content     TEXT,
    metrics     float[],
    is_public   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_progression_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_progression_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_progression_program ON progression_entries(program_id);
CREATE INDEX IF NOT EXISTS idx_progression_user ON progression_entries(user_id);
CREATE INDEX IF NOT EXISTS idx_progression_created ON progression_entries(created_at);
