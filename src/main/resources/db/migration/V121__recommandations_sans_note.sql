-- P-BL-10 étape 4 (décision du 13/09) — on recommande quelqu'un, on ne le note plus.
--
-- données : les notes déjà saisies (10 lignes relevées en production le 13/09)
-- l'ont été sous une promesse que le produit retire ; plus rien ne les écrit ni
-- ne les lit depuis la livraison des étapes 1 à 3. Irréversible, décidé par le
-- propriétaire du dépôt le 13/09.

UPDATE peer_recommendations SET rating = NULL WHERE rating IS NOT NULL;

ALTER TABLE peer_recommendations DROP COLUMN IF EXISTS rating;
