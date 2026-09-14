-- ============================================================
-- V122 — Les badges : ni série, ni palier, ni note
-- ============================================================
-- Demande mobile du 14/09/2026 (modules/badges, P-MU-25). L'app masquait déjà
-- les badges de série ; le serveur continuait de les attribuer. Un badge qu'on
-- attribue sans jamais le montrer reste une donnée sur la régularité de
-- quelqu'un que personne n'a demandée.
--
-- Trois familles sortent du catalogue, et leurs attributions avec elles
-- (fk_badge_awards_badge est ON DELETE CASCADE) : les supprimer plutôt que les
-- garder en base sans les servir, parce qu'une donnée gardée « au cas où » finit
-- toujours par être resservie.
--
-- 1. Les séries : une série mesure un effort dans la durée.

DELETE FROM badges
WHERE condition_type IN ('PROGRESSION_STREAK', 'STREAK_DAYS', 'WEEKLY_STREAK');

-- 2. Les notes : plus aucune note ni moyenne publique depuis la décision D4 du
--    13/09 (P-BL-10). « Top Bewertet » et « Perfekte Bewertung » en étaient une.

DELETE FROM badges
WHERE condition_type IN ('AVERAGE_REVIEW_SCORE', 'PERFECT_REVIEWS');

-- 3. Les paliers : deux badges sur la même condition à des seuils différents
--    (TRUSTED_5 puis TRUSTED_20 après FIRST_RECOMMENDATION, TEN_MEETUPS après
--    FIRST_MEETUP…) transforment la condition en échelle à gravir. Seul le
--    seuil le plus bas reste : il marque un geste, il ne mesure pas une
--    performance. Les badges de vérification ne sont pas des paliers
--    (e-mail, téléphone, identité sont trois faits distincts), les manuels non plus.

DELETE FROM badges b
WHERE b.condition_type NOT IN ('VERIFICATION', 'MANUAL')
  AND b.condition_threshold > (
      SELECT MIN(autre.condition_threshold)
      FROM badges autre
      WHERE autre.condition_type = b.condition_type
  );

-- Un chiffre dans le nom servi est un compte affiché.

UPDATE badges SET label = 'Partenaires variés'
WHERE code = 'FIVE_PARTNERS';

-- La base refuse désormais qu'un badge de série ou de note revienne, par une
-- migration ou par la graine de référence : c'est l'interrupteur côté serveur.

ALTER TABLE badges
    ADD CONSTRAINT chk_badges_ni_serie_ni_note
    CHECK (condition_type NOT IN ('PROGRESSION_STREAK', 'STREAK_DAYS', 'WEEKLY_STREAK',
                                  'AVERAGE_REVIEW_SCORE', 'PERFECT_REVIEWS'));
