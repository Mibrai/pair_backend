-- Fix peer_recommendations: rename columns (idempotent) and add missing ones
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='peer_recommendations' AND column_name='from_user_id') THEN
    ALTER TABLE peer_recommendations RENAME COLUMN from_user_id TO recommender_id;
  END IF;
END $$;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='peer_recommendations' AND column_name='to_user_id') THEN
    ALTER TABLE peer_recommendations RENAME COLUMN to_user_id TO recommended_id;
  END IF;
END $$;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='peer_recommendations' AND column_name='interaction_proof_id') THEN
    ALTER TABLE peer_recommendations RENAME COLUMN interaction_proof_id TO conversation_id;
  END IF;
END $$;
ALTER TABLE peer_recommendations ADD COLUMN IF NOT EXISTS rating INTEGER;
ALTER TABLE peer_recommendations ADD COLUMN IF NOT EXISTS activity_context UUID;
ALTER TABLE peer_recommendations ADD COLUMN IF NOT EXISTS program_context UUID;
ALTER TABLE peer_recommendations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE peer_recommendations DROP CONSTRAINT IF EXISTS uq_peer_rec_from_to;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='unique_recommendation') THEN
    ALTER TABLE peer_recommendations ADD CONSTRAINT unique_recommendation UNIQUE (recommender_id, recommended_id);
  END IF;
END $$;

-- Fix reports: rename columns (idempotent) and add missing ones
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='reports' AND column_name='target_type') THEN
    ALTER TABLE reports RENAME COLUMN target_type TO reported_entity_type;
  END IF;
END $$;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='reports' AND column_name='target_id') THEN
    ALTER TABLE reports RENAME COLUMN target_id TO reported_entity_id;
  END IF;
END $$;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS description VARCHAR(500);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reviewed_by UUID;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolution_notes TEXT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- Fix search_logs: rename column (idempotent)
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='search_logs' AND column_name='created_at') THEN
    ALTER TABLE search_logs RENAME COLUMN created_at TO searched_at;
  END IF;
END $$;
ALTER TABLE search_logs ADD COLUMN IF NOT EXISTS search_method VARCHAR(50) DEFAULT 'fulltext';

-- Create message_edit_history table (missing)
CREATE TABLE IF NOT EXISTS message_edit_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id       UUID NOT NULL,
    previous_content VARCHAR(4000) NOT NULL,
    edited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_edit_history_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_edit_history_message ON message_edit_history(message_id);
CREATE INDEX IF NOT EXISTS idx_edit_history_edited_at ON message_edit_history(edited_at);

-- Create progressions table (missing)
CREATE TABLE IF NOT EXISTS progressions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id   UUID NOT NULL,
    user_id      UUID NOT NULL,
    title        VARCHAR(150),
    content      TEXT,
    metrics      float[],
    metric_labels text[],
    is_public    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ,
    CONSTRAINT fk_progressions_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_progressions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_progressions_program ON progressions(program_id, created_at);
CREATE INDEX IF NOT EXISTS idx_progressions_user ON progressions(user_id, created_at);

-- Create program_enrollments table (missing)
CREATE TABLE IF NOT EXISTS program_enrollments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    program_id  UUID NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at     TIMESTAMPTZ,
    left_reason VARCHAR(500),
    CONSTRAINT fk_enrollments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_enrollments_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT uq_enrollment UNIQUE (user_id, program_id)
);
CREATE INDEX IF NOT EXISTS idx_enrollments_user ON program_enrollments(user_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_program ON program_enrollments(program_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_status ON program_enrollments(status);
CREATE INDEX IF NOT EXISTS idx_enrollments_enrolled_at ON program_enrollments(enrolled_at);

-- Create program_progress table (missing)
CREATE TABLE IF NOT EXISTS program_progress (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_program_id UUID NOT NULL,
    activity_id     UUID NOT NULL,
    completed_at    TIMESTAMPTZ,
    skipped         BOOLEAN NOT NULL DEFAULT FALSE,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT fk_program_progress_enrollment FOREIGN KEY (user_program_id) REFERENCES program_enrollments(id) ON DELETE CASCADE,
    CONSTRAINT uq_program_progress UNIQUE (user_program_id, activity_id)
);
CREATE INDEX IF NOT EXISTS idx_progress_user_program ON program_progress(user_program_id);
CREATE INDEX IF NOT EXISTS idx_progress_activity ON program_progress(activity_id);
CREATE INDEX IF NOT EXISTS idx_progress_completed_at ON program_progress(completed_at);
