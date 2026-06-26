-- ==================================================
-- Phase 3 Module 4: Content Moderation (Reports)
-- ==================================================
-- Date: 2026-06-23
-- Description: Système de signalements pour modération

-- Create enums
CREATE TYPE report_entity_type AS ENUM ('USER', 'PROGRAM', 'MESSAGE', 'REVIEW');
CREATE TYPE report_reason AS ENUM (
    'SPAM',
    'HARASSMENT',
    'INAPPROPRIATE_CONTENT',
    'FAKE_PROFILE',
    'VIOLENCE',
    'HATE_SPEECH',
    'OTHER'
);
CREATE TYPE report_status AS ENUM ('PENDING', 'REVIEWED', 'ACTIONED', 'DISMISSED');

CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who reports
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- What is reported
    reported_entity_type report_entity_type NOT NULL,
    reported_entity_id UUID NOT NULL,

    -- Why
    reason report_reason NOT NULL,
    description TEXT CHECK (char_length(description) >= 10 AND char_length(description) <= 500),

    -- Status & resolution
    status report_status NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    resolution_notes TEXT,

    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints: one report per user/entity combination
    CONSTRAINT unique_report UNIQUE (reporter_id, reported_entity_type, reported_entity_id)
);

-- Indexes
CREATE INDEX idx_reports_reporter ON reports(reporter_id);
CREATE INDEX idx_reports_entity ON reports(reported_entity_type, reported_entity_id);
CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_reason ON reports(reason);
CREATE INDEX idx_reports_created ON reports(created_at DESC);
CREATE INDEX idx_reports_pending ON reports(status, created_at) WHERE status = 'PENDING';

-- Trigger for updated_at
CREATE TRIGGER update_reports_updated_at
BEFORE UPDATE ON reports
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- ==================================================
-- Vue: Statistiques des signalements
-- ==================================================
CREATE OR REPLACE VIEW report_stats AS
SELECT
    reported_entity_type,
    reported_entity_id,
    COUNT(*) AS report_count,
    COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
    COUNT(*) FILTER (WHERE status = 'ACTIONED') AS actioned_count,
    MAX(created_at) AS last_report_at
FROM reports
GROUP BY reported_entity_type, reported_entity_id;

-- ==================================================
-- Résumé:
-- - Table reports avec types d'entités (USER, PROGRAM, MESSAGE, REVIEW)
-- - Raisons prédéfinies (SPAM, HARASSMENT, etc.)
-- - Workflow de modération (PENDING → REVIEWED → ACTIONED/DISMISSED)
-- - Description 10-500 caractères
-- - Un seul report par user/entity
-- - Vue stats pour dashboard modération
-- ==================================================

COMMIT;
