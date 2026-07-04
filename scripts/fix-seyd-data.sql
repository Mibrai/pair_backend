-- Fix données invalides pour seyd.njoya@icloud.com
-- 1. Corrige les valeurs format invalides (IN_PERSON, BOTH -> valeurs enum Java valides)
UPDATE user_activities
SET format = CASE format
    WHEN 'IN_PERSON' THEN 'GROUP'
    WHEN 'BOTH'      THEN 'ANY'
    ELSE format
END
WHERE format IN ('IN_PERSON', 'BOTH')
  AND user_id = (SELECT id FROM users WHERE email = 'seyd.njoya@icloud.com');

-- 2. Corrige les valeurs status invalides dans programs (PUBLISHED -> ACTIVE)
UPDATE programs
SET status = 'ACTIVE'
WHERE status = 'PUBLISHED'
  AND user_activity_id IN (
      SELECT ua.id FROM user_activities ua
      JOIN users u ON ua.user_id = u.id
      WHERE u.email = 'seyd.njoya@icloud.com'
  );

-- Vérification
SELECT 'user_activities formats' AS check, format, COUNT(*) FROM user_activities
WHERE user_id = (SELECT id FROM users WHERE email = 'seyd.njoya@icloud.com')
GROUP BY format
UNION ALL
SELECT 'programs status', p.status::text, COUNT(*) FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
WHERE u.email = 'seyd.njoya@icloud.com'
GROUP BY p.status;
