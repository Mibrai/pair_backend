-- GET /api/categories mélangeait trois formats incompatibles pour color_ramp :
-- nom de rampe ("orange-red"), hexadécimal (les 10 catégories de
-- seed/data/categories.json, chargées par ReferenceDataSeeder), et NULL.
-- On normalise sur un seul format : le nom de rampe, qui laisse chaque
-- client appliquer sa propre identité visuelle.

-- 1. Les 10 valeurs hexadécimales connues (palette Tailwind utilisée dans
--    categories.json) vers leur équivalent nom-de-rampe déjà en usage.
UPDATE categories SET color_ramp = 'red-orange'     WHERE color_ramp = '#EF4444';
UPDATE categories SET color_ramp = 'purple-violet'  WHERE color_ramp = '#8B5CF6';
UPDATE categories SET color_ramp = 'amber-brown'    WHERE color_ramp = '#F59E0B';
UPDATE categories SET color_ramp = 'orange-yellow'  WHERE color_ramp = '#F97316';
UPDATE categories SET color_ramp = 'blue-indigo'    WHERE color_ramp = '#3B82F6';
UPDATE categories SET color_ramp = 'green-teal'     WHERE color_ramp = '#10B981';
UPDATE categories SET color_ramp = 'pink-rose'      WHERE color_ramp = '#EC4899';
UPDATE categories SET color_ramp = 'cyan-blue'      WHERE color_ramp = '#06B6D4';
UPDATE categories SET color_ramp = 'purple-pink'    WHERE color_ramp = '#A855F7';
UPDATE categories SET color_ramp = 'sky-indigo'     WHERE color_ramp = '#6366F1';

-- 2. Toute valeur hexadécimale restante (non prévue ci-dessus, ex. ajoutée
--    manuellement en production) : normalisée sur un nom de rampe choisi de
--    façon déterministe à partir de l'id, plutôt que laissée telle quelle.
-- 3. Toute valeur NULL : même traitement déterministe.
WITH palette(idx, ramp) AS (
    VALUES (0,'orange-red'), (1,'purple-violet'), (2,'brown-amber'), (3,'blue-indigo'),
           (4,'green-teal'), (5,'cyan-blue'), (6,'red-orange'), (7,'pink-rose'),
           (8,'lime-green'), (9,'sky-blue')
)
UPDATE categories c
SET color_ramp = p.ramp
FROM palette p
WHERE (c.color_ramp IS NULL OR c.color_ramp LIKE '#%')
  AND p.idx = (('x' || substr(md5(c.id::text), 1, 8))::bit(32)::bigint % 10);

ALTER TABLE categories
    ALTER COLUMN color_ramp SET NOT NULL;
