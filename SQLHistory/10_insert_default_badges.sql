-- ==================================================
-- Phase 3 Module 1: Badges par Défaut
-- ==================================================
-- Date: 2026-06-23
-- Description: Création des badges prédéfinis du système

-- Verification badges
-- Note: table structure uses: code, category, label, condition_type, condition_threshold, icon
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
VALUES
(
    '00000001-0000-0000-0000-000000000001',
    'VERIFIED_EMAIL',
    'VERIFICATION',
    'Email Vérifié',
    'VERIFICATION',
    0,
    '🔒'
),
(
    '00000001-0000-0000-0000-000000000002',
    'VERIFIED_PHONE',
    'Téléphone Vérifié',
    'A vérifié son numéro de téléphone',
    '📱',
    'VERIFICATION',
    0
);

-- Program creation badges
INSERT INTO badges (id, code, name, description, icon_url, condition_type, condition_threshold)
VALUES
(
    '00000002-0000-0000-0000-000000000001',
    'PROGRAM_CREATOR',
    'Créateur',
    'A créé son premier programme',
    '🎯',
    'PROGRAM_COUNT',
    1
),
(
    '00000002-0000-0000-0000-000000000002',
    'SUPER_HOST',
    'Super Hôte',
    'A créé 5 programmes ou plus',
    '⭐',
    'PROGRAM_COUNT',
    5
),
(
    '00000002-0000-0000-0000-000000000003',
    'MEGA_HOST',
    'Méga Hôte',
    'A créé 10 programmes ou plus',
    '🏆',
    'PROGRAM_COUNT',
    10
);

-- Progression streak badges
INSERT INTO badges (id, code, name, description, icon_url, condition_type, condition_threshold)
VALUES
(
    '00000003-0000-0000-0000-000000000001',
    'STREAK_7',
    'Régulier',
    'A maintenu une série de 7 jours',
    '🔥',
    'PROGRESSION_STREAK',
    7
),
(
    '00000003-0000-0000-0000-000000000002',
    'STREAK_30',
    'Assidu',
    'A maintenu une série de 30 jours',
    '💪',
    'PROGRESSION_STREAK',
    30
),
(
    '00000003-0000-0000-0000-000000000003',
    'STREAK_100',
    'Champion',
    'A maintenu une série de 100 jours',
    '👑',
    'PROGRESSION_STREAK',
    100
);

-- Activity diversity badges
INSERT INTO badges (id, code, name, description, icon_url, condition_type, condition_threshold)
VALUES
(
    '00000004-0000-0000-0000-000000000001',
    'MULTI_SPORT',
    'Polyvalent',
    'Pratique 3 activités ou plus',
    '🎨',
    'ACTIVITY_DIVERSITY',
    3
),
(
    '00000004-0000-0000-0000-000000000002',
    'JACK_OF_ALL',
    'Touche à tout',
    'Pratique 5 activités ou plus',
    '🌟',
    'ACTIVITY_DIVERSITY',
    5
),
(
    '00000004-0000-0000-0000-000000000003',
    'MASTER_ALL',
    'Expert Universel',
    'Pratique 8 activités ou plus',
    '💎',
    'ACTIVITY_DIVERSITY',
    8
);

-- Recommendation badges (Phase 3 Module 2)
INSERT INTO badges (id, code, name, description, icon_url, condition_type, condition_threshold)
VALUES
(
    '00000005-0000-0000-0000-000000000001',
    'TRUSTED',
    'De Confiance',
    'A reçu 3 recommandations',
    '🤝',
    'RECOMMENDATION_COUNT',
    3
),
(
    '00000005-0000-0000-0000-000000000002',
    'HIGHLY_TRUSTED',
    'Très Fiable',
    'A reçu 10 recommandations',
    '💙',
    'RECOMMENDATION_COUNT',
    10
),
(
    '00000005-0000-0000-0000-000000000003',
    'COMMUNITY_HERO',
    'Héros de la Communauté',
    'A reçu 25 recommandations',
    '🦸',
    'RECOMMENDATION_COUNT',
    25
);

-- Special manual badges
INSERT INTO badges (id, code, name, description, icon_url, condition_type, condition_threshold)
VALUES
(
    '00000006-0000-0000-0000-000000000001',
    'EARLY_ADOPTER',
    'Early Adopter',
    'Membre fondateur de Pair',
    '🚀',
    'MANUAL',
    0
),
(
    '00000006-0000-0000-0000-000000000002',
    'MODERATOR',
    'Modérateur',
    'Aide à modérer la communauté',
    '🛡️',
    'MANUAL',
    0
),
(
    '00000006-0000-0000-0000-000000000003',
    'CONTRIBUTOR',
    'Contributeur',
    'A contribué au développement de Pair',
    '💻',
    'MANUAL',
    0
);

-- ==================================================
-- Résumé:
-- - 15 badges créés
-- - 6 catégories: Verification, Programs, Streaks, Activities, Recommendations, Special
-- - Icônes emoji (placeholder, à remplacer par URLs)
-- ==================================================

COMMIT;
