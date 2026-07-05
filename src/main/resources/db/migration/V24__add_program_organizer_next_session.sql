-- V24: Add denormalized organizer and next-session fields to programs.
--
-- organizer_name      — display_name of the user who owns the program
--                       (programs → user_activities → users)
-- organizer_avatar_url — avatar_url of that user
-- next_session_at     — earliest future schedule for this program
--                       (programs → schedules WHERE starts_at > NOW())
--
-- These columns are kept in sync at write time by the application layer;
-- this migration performs the initial backfill.

ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS organizer_name       VARCHAR(80),
    ADD COLUMN IF NOT EXISTS organizer_avatar_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS next_session_at      TIMESTAMPTZ;

-- ============================================================
-- Backfill organizer_name and organizer_avatar_url
-- ============================================================
UPDATE programs p
SET
    organizer_name       = u.display_name,
    organizer_avatar_url = u.avatar_url
FROM user_activities ua
JOIN users u ON u.id = ua.user_id
WHERE ua.id = p.user_activity_id;

-- ============================================================
-- Backfill next_session_at (earliest upcoming schedule)
-- ============================================================
UPDATE programs p
SET next_session_at = s.next_at
FROM (
    SELECT program_id, MIN(starts_at) AS next_at
    FROM   schedules
    WHERE  starts_at > NOW()
    GROUP  BY program_id
) s
WHERE s.program_id = p.id;
