-- Run this query to find all badge categories in your database
-- Example: psql -U pair_user -d pair_db -f find_badge_categories.sql

SELECT DISTINCT category
FROM badges
ORDER BY category;
