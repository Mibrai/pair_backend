-- Create user_programs table for program enrollments
CREATE TABLE IF NOT EXISTS user_programs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    program_id          UUID NOT NULL,
    schedule_id         UUID,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    leave_reason        TEXT,
    progress_percentage INTEGER NOT NULL DEFAULT 0,
    activities_completed INTEGER NOT NULL DEFAULT 0,
    activities_skipped  INTEGER NOT NULL DEFAULT 0,
    last_activity_at    TIMESTAMPTZ,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at             TIMESTAMPTZ,
    CONSTRAINT fk_user_programs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_programs_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_programs_schedule FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE SET NULL,
    CONSTRAINT uk_user_program_active UNIQUE (user_id, program_id, status)
);

CREATE INDEX IF NOT EXISTS idx_user_programs_user ON user_programs(user_id);
CREATE INDEX IF NOT EXISTS idx_user_programs_program ON user_programs(program_id);
CREATE INDEX IF NOT EXISTS idx_user_programs_schedule ON user_programs(schedule_id);
CREATE INDEX IF NOT EXISTS idx_user_programs_status ON user_programs(status);
CREATE INDEX IF NOT EXISTS idx_user_programs_joined ON user_programs(joined_at);

-- Create program_activities table for tracking activity completion
CREATE TABLE IF NOT EXISTS program_activities (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_program_id     UUID NOT NULL,
    activity_id         UUID NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    completed_at        TIMESTAMPTZ,
    skipped_at          TIMESTAMPTZ,
    notes               TEXT,
    CONSTRAINT fk_program_activities_user_program FOREIGN KEY (user_program_id) REFERENCES user_programs(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_program_activity UNIQUE (user_program_id, activity_id)
);

CREATE INDEX IF NOT EXISTS idx_program_activities_user_program ON program_activities(user_program_id);
CREATE INDEX IF NOT EXISTS idx_program_activities_status ON program_activities(status);
