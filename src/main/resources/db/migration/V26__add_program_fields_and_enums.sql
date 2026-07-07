-- V26: Add missing program fields required by the frontend
--
-- 1. New columns in programs table
-- 2. No DB-level enum constraints needed (Java @Enumerated(STRING) + VARCHAR columns)

ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS duration_weeks              INTEGER,
    ADD COLUMN IF NOT EXISTS sessions_per_week           INTEGER,
    ADD COLUMN IF NOT EXISTS session_duration_minutes    INTEGER,
    ADD COLUMN IF NOT EXISTS preferred_days              INTEGER[],
    ADD COLUMN IF NOT EXISTS preferred_time              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS max_participants            INTEGER,
    ADD COLUMN IF NOT EXISTS privacy                     VARCHAR(20) DEFAULT 'PUBLIC',
    ADD COLUMN IF NOT EXISTS goals                       TEXT,
    ADD COLUMN IF NOT EXISTS prerequisites               TEXT,
    ADD COLUMN IF NOT EXISTS location_type               VARCHAR(20);

DO $$
BEGIN
    RAISE NOTICE 'V26 — programs: 10 new columns added (duration_weeks, sessions_per_week, session_duration_minutes, preferred_days, preferred_time, max_participants, privacy, goals, prerequisites, location_type)';
END $$;
