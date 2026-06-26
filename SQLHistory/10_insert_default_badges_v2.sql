-- ==================================================
-- Phase 3 Module 1: Badges par Défaut (v2 - correct schema)
-- ==================================================
-- Date: 2026-06-23
-- Schema: code, category, label, condition_type, condition_threshold, icon

-- Verification badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000001-0000-0000-0000-000000000001', 'VERIFIED_EMAIL', 'VERIFICATION', 'Email Vérifié', 'VERIFICATION', 0, '🔒'),
('00000001-0000-0000-0000-000000000002', 'VERIFIED_PHONE', 'VERIFICATION', 'Téléphone Vérifié', 'VERIFICATION', 0, '📱');

-- Program creation badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000002-0000-0000-0000-000000000001', 'PROGRAM_CREATOR', 'ACHIEVEMENT', 'Créateur', 'PROGRAM_COUNT', 1, '🎯'),
('00000002-0000-0000-0000-000000000002', 'SUPER_HOST', 'ACHIEVEMENT', 'Super Hôte', 'PROGRAM_COUNT', 5, '⭐'),
('00000002-0000-0000-0000-000000000003', 'MEGA_HOST', 'ACHIEVEMENT', 'Méga Hôte', 'PROGRAM_COUNT', 10, '🏆');

-- Progression streak badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000003-0000-0000-0000-000000000001', 'STREAK_7', 'ENGAGEMENT', 'Régulier', 'PROGRESSION_STREAK', 7, '🔥'),
('00000003-0000-0000-0000-000000000002', 'STREAK_30', 'ENGAGEMENT', 'Assidu', 'PROGRESSION_STREAK', 30, '💪'),
('00000003-0000-0000-0000-000000000003', 'STREAK_100', 'ENGAGEMENT', 'Champion', 'PROGRESSION_STREAK', 100, '👑');

-- Activity diversity badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000004-0000-0000-0000-000000000001', 'MULTI_SPORT', 'ACHIEVEMENT', 'Polyvalent', 'ACTIVITY_DIVERSITY', 3, '🎨'),
('00000004-0000-0000-0000-000000000002', 'JACK_OF_ALL', 'ACHIEVEMENT', 'Touche à tout', 'ACTIVITY_DIVERSITY', 5, '🌟'),
('00000004-0000-0000-0000-000000000003', 'MASTER_ALL', 'ACHIEVEMENT', 'Expert Universel', 'ACTIVITY_DIVERSITY', 8, '💎');

-- Recommendation badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000005-0000-0000-0000-000000000001', 'TRUSTED', 'TRUST', 'De Confiance', 'RECOMMENDATION_COUNT', 3, '🤝'),
('00000005-0000-0000-0000-000000000002', 'HIGHLY_TRUSTED', 'TRUST', 'Très Fiable', 'RECOMMENDATION_COUNT', 10, '💙'),
('00000005-0000-0000-0000-000000000003', 'COMMUNITY_HERO', 'TRUST', 'Héros Communauté', 'RECOMMENDATION_COUNT', 25, '🦸');

-- Special manual badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
('00000006-0000-0000-0000-000000000001', 'EARLY_ADOPTER', 'SPECIAL', 'Early Adopter', 'MANUAL', 0, '🚀'),
('00000006-0000-0000-0000-000000000002', 'MODERATOR', 'SPECIAL', 'Modérateur', 'MANUAL', 0, '🛡️'),
('00000006-0000-0000-0000-000000000003', 'CONTRIBUTOR', 'SPECIAL', 'Contributeur', 'MANUAL', 0, '💻');

-- ==================================================
-- 15 badges créés
-- Categories: VERIFICATION, ACHIEVEMENT, ENGAGEMENT, TRUST, SPECIAL
-- ==================================================

COMMIT;
