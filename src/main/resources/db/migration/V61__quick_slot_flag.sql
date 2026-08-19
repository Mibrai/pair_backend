-- Chemin court « je cherche quelqu'un pour… » (lot A2 de meetdo-v2)
--
-- Deux choses, liées par le même lot.

-- 1. Comment le programme a été créé.
--
-- Un programme né du chemin court n'a ni description, ni objectifs, ni durée :
-- il n'a qu'un titre auto-généré et une séance. Sans cette colonne, rien ne
-- distingue « l'auteur n'a pas rempli ces champs » de « on ne les lui a jamais
-- demandés » — et c'est cette distinction qui permettra plus tard de proposer
-- « transformer en programme complet » à qui de droit, sans harceler ceux qui
-- ont délibérément laissé des champs vides.
--
-- Le DEFAULT couvre l'existant : tout ce qui est déjà en base est passé par le
-- formulaire complet.
ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS created_via VARCHAR(20) NOT NULL DEFAULT 'FULL';

-- 2. Un créneau en ligne n'a pas de coordonnées.
--
-- schedules.location est NOT NULL depuis V5, alors que le code ne pose aucune
-- position quand le lieu est ONLINE : créer un créneau en ligne par l'API échoue
-- donc en violation de contrainte, pas avec une erreur métier. Le symptôme
-- signalé par le terrain — des créneaux enregistrés en 0,0 — vient de là : la
-- validation accepte lat=0 et lng=0, et c'est le contournement naturel de qui se
-- heurte à un 500 sans explication.
--
-- Ce n'était pas un choix de modèle mais un oubli, et le reste du code le montre :
-- SlotAddressVisibility traite déjà le cas d'une position nulle, et le test
-- d'intégration de la recherche construit un créneau ONLINE avec location(null)
-- en court-circuitant l'API.
--
-- La contrainte n'est pas simplement levée : elle est remplacée par la règle
-- qu'elle voulait dire. Un lieu physique sans position resterait une anomalie —
-- il ne serait sur aucune carte, dans aucun rayon, et personne ne saurait où
-- aller.
ALTER TABLE schedules ALTER COLUMN location DROP NOT NULL;

ALTER TABLE schedules DROP CONSTRAINT IF EXISTS chk_schedule_location_unless_online;
ALTER TABLE schedules ADD CONSTRAINT chk_schedule_location_unless_online
    CHECK (place_type = 'ONLINE' OR location IS NOT NULL);
