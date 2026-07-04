-- V23: Assign Google Material Icons to categories and activities.
-- Icon values are Material Icons ligature names (https://fonts.google.com/icons).
-- Frontend renders them via <span class="material-icons">{name}</span> or equivalent.

-- ============================================================
-- CATEGORIES — update icon field
-- ============================================================
UPDATE categories SET icon = 'groups'              WHERE id = 'bbbbbbbb-0000-0000-0000-000000000001'; -- Sports collectifs
UPDATE categories SET icon = 'directions_run'      WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002'; -- Sports individuels
UPDATE categories SET icon = 'spa'                 WHERE id = 'bbbbbbbb-0000-0000-0000-000000000003'; -- Bien-être
UPDATE categories SET icon = 'sports_martial_arts' WHERE id = 'bbbbbbbb-0000-0000-0000-000000000004'; -- Arts martiaux
UPDATE categories SET icon = 'landscape'           WHERE id = 'bbbbbbbb-0000-0000-0000-000000000005'; -- Plein air

-- ============================================================
-- ACTIVITIES — update icon field
-- ============================================================
UPDATE activities SET icon = 'sports_soccer'       WHERE id = 'cccccccc-0000-0000-0000-000000000001'; -- Football
UPDATE activities SET icon = 'sports_basketball'   WHERE id = 'cccccccc-0000-0000-0000-000000000002'; -- Basketball
UPDATE activities SET icon = 'pool'                WHERE id = 'cccccccc-0000-0000-0000-000000000003'; -- Natation
UPDATE activities SET icon = 'directions_bike'     WHERE id = 'cccccccc-0000-0000-0000-000000000004'; -- Cyclisme
UPDATE activities SET icon = 'directions_run'      WHERE id = 'cccccccc-0000-0000-0000-000000000005'; -- Running
UPDATE activities SET icon = 'self_improvement'    WHERE id = 'cccccccc-0000-0000-0000-000000000006'; -- Yoga
UPDATE activities SET icon = 'spa'                 WHERE id = 'cccccccc-0000-0000-0000-000000000007'; -- Méditation
UPDATE activities SET icon = 'sports_martial_arts' WHERE id = 'cccccccc-0000-0000-0000-000000000008'; -- Judo
UPDATE activities SET icon = 'sports_martial_arts' WHERE id = 'cccccccc-0000-0000-0000-000000000009'; -- Karaté
UPDATE activities SET icon = 'hiking'              WHERE id = 'cccccccc-0000-0000-0000-000000000010'; -- Randonnée
