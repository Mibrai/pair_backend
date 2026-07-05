-- V25: Fix data inconsistencies introduced by seed scripts
--
-- 1. programs.status = 'PUBLISHED' is not a valid Java enum value
--    (ProgramStatus has DRAFT, ACTIVE, PAUSED, ARCHIVED) → remap to ACTIVE
-- 2. user_activities.format has invalid values 'IN_PERSON' and 'BOTH'
--    (ActivityFormat has SOLO, DUO, GROUP, ANY) → remap to valid values
-- 3. programs.organizer_name / organizer_avatar_url may still be NULL
--    for programs seeded by scripts that ran after V24 without setting these columns

-- Fix 1: invalid program status
DO $$
DECLARE n INT;
BEGIN
    UPDATE programs SET status = 'ACTIVE' WHERE status = 'PUBLISHED';
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V25 fix1 — programs.status PUBLISHED→ACTIVE: % rows', n;
END $$;

-- Fix 2: invalid user_activity format values
DO $$
DECLARE n INT;
BEGIN
    UPDATE user_activities
    SET format = CASE format
        WHEN 'IN_PERSON' THEN 'GROUP'
        WHEN 'BOTH'      THEN 'ANY'
        ELSE format
    END
    WHERE format IN ('IN_PERSON', 'BOTH');
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V25 fix2 — user_activities.format invalid→valid: % rows', n;
END $$;

-- Fix 3: backfill organizer columns for programs where they are still NULL
DO $$
DECLARE n INT;
BEGIN
    UPDATE programs p
    SET
        organizer_name       = u.display_name,
        organizer_avatar_url = u.avatar_url
    FROM user_activities ua
    JOIN users u ON u.id = ua.user_id
    WHERE ua.id = p.user_activity_id
      AND (p.organizer_name IS NULL OR p.organizer_avatar_url IS NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V25 fix3 — programs.organizer_name backfill: % rows', n;
END $$;
