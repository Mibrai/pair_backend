-- ============================================================
-- Backfill: organizer_name, organizer_avatar_url, next_session_at
-- ============================================================
-- Run this directly against the DB after applying migration V24.
-- The schedules seeded by V13/V12 have starts_at relative to the
-- migration date and are likely now in the past, so this script
-- also pushes them into the future before computing next_session_at.
-- ============================================================

BEGIN;

-- ============================================================
-- 1. organizer_name + organizer_avatar_url
--    Source: programs → user_activities → users
-- ============================================================
UPDATE programs p
SET
    organizer_name       = u.display_name,
    organizer_avatar_url = u.avatar_url
FROM user_activities ua
JOIN users u ON u.id = ua.user_id
WHERE ua.id = p.user_activity_id;

-- ============================================================
-- 2. Refresh schedule dates to random future slots
--    Each schedule gets a unique random offset so starts_at
--    are spread across the next 60 days, hours 6-19.
-- ============================================================
UPDATE schedules s
SET
    starts_at = base.new_start,
    ends_at   = base.new_start + (FLOOR(random() * 2 + 1)::INT || ' hours')::INTERVAL
FROM (
    SELECT
        id,
        NOW()
            + (FLOOR(random() * 59 + 1)::INT || ' days' )::INTERVAL
            + (FLOOR(random() * 13 + 6)::INT || ' hours')::INTERVAL AS new_start
    FROM schedules
) base
WHERE s.id = base.id;

-- ============================================================
-- 3. next_session_at = earliest future schedule per program
-- ============================================================
UPDATE programs p
SET next_session_at = agg.next_at
FROM (
    SELECT program_id, MIN(starts_at) AS next_at
    FROM   schedules
    WHERE  starts_at > NOW()
    GROUP  BY program_id
) agg
WHERE agg.program_id = p.id;

-- Programs with no schedules at all keep next_session_at = NULL
-- (already the default — no explicit NULL-set needed)

COMMIT;
