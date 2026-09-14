-- ============================================================
-- V126 — Plus de série de pratique
-- ============================================================
-- Demande mobile du 14/09/2026 (modules/badges, BIS), décision de l'utilisateur :
-- ni stats d'effort ni séries (P-MU-25). « 5 semaines d'affilée » était calculé à
-- chaque confirmation de présence et servi par /users/me/practice-stats, que
-- l'app ne lisait plus.
--
-- La colonne est supprimée, et les séries déjà calculées avec elle, pour la
-- raison de V122 : une série gardée « au cas où » reste une donnée sur la
-- régularité de quelqu'un que personne n'a demandée.
--
-- Retour arrière vers un code antérieur, qui mappe encore la colonne :
--   ALTER TABLE users ADD COLUMN current_streak_weeks INTEGER NOT NULL DEFAULT 0;
-- Les valeurs ne reviennent pas : elles se recalculeraient à la présence suivante.

ALTER TABLE users DROP COLUMN IF EXISTS current_streak_weeks;
