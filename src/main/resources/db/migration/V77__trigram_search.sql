-- Tolérance aux fautes de frappe (lot D7 de meetdo-v2)
--
-- « yoag », « escallade », « Klettren » : trois requêtes qui ne rendent rien
-- aujourd'hui, et dont l'auteur conclut que l'application est vide plutôt qu'il
-- s'est trompé d'une lettre.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Index GIN sur trigrammes. Ils servent l'opérateur de similarité (%) et la
-- fonction similarity() ; sans eux, chaque requête approximative devient un scan
-- complet des deux tables, ce qui est acceptable sur un référentiel de quelques
-- centaines de lignes et ne l'est plus sur les programmes.
CREATE INDEX IF NOT EXISTS idx_activities_name_trgm
    ON activities USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_programs_title_trgm
    ON programs USING gin (title gin_trgm_ops);

-- Ce que ces index ne changent pas, et pourquoi c'est important.
--
-- La similarité trigramme est une QUATRIÈME couche, et elle ne s'exécute qu'en
-- REPLI : uniquement quand la taxonomie, le sémantique et le plein texte n'ont
-- rien rendu. La placer avant, ou la fusionner avec les autres, ferait remonter
-- des résultats vaguement ressemblants au-dessus de résultats exacts — « Yoga »
-- et « Toga » partagent trois trigrammes sur quatre, et la mesure ne sait pas
-- qu'un seul des deux est un mot.
--
-- Elle ne remplace pas non plus le cross-lingue. « Klettern » et « escalade »
-- n'ont aucun trigramme commun : c'est la taxonomie qui les rapproche, et elle
-- passe en premier. Le trigramme ne rattrape que la faute de frappe, dans la
-- langue où elle a été faite.
