-- Confort de messagerie (lot D5 de meetdo-v2)
--
-- Trois ajouts sans rapport de parenté, réunis parce qu'ils portent sur la même
-- surface : l'indicateur de saisie ne touche pas la base (il ne survit pas à sa
-- seconde), la sourdine et l'archivage vivent sur l'appartenance, le partage de
-- position vit sur le message.

-- 1. Sourdine et archivage, par conversation et par personne.
--
-- Sur conversation_members et non sur conversations : deux personnes d'un même
-- fil n'ont aucune raison de le classer pareil, et le poser sur la conversation
-- ferait qu'archiver chez l'un archiverait chez l'autre.
--
-- Des dates plutôt que des booléens, pour la raison déjà retenue en V60 :
-- « depuis quand » se révélera utile — mesurer ce qu'on met en sourdine, savoir
-- si un archivage précède un abandon — et un booléen ne se transforme pas
-- rétroactivement en date.
ALTER TABLE conversation_members
    ADD COLUMN IF NOT EXISTS muted_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

-- 2. Partage de position ponctuel, porté par le message qui l'annonce.
--
-- Colonnes flottantes et non un point PostGIS, contrairement à schedules et
-- users. Ce point n'est jamais interrogé spatialement : il n'est ni cherché dans
-- un rayon, ni trié par distance, ni agrégé — il est affiché dans une bulle de
-- conversation, puis il expire. Lui donner un type géographique et un index
-- l'aurait rangé avec les données que le système interroge, et invité la
-- première requête « qui était près d'ici » à s'y servir. C'est exactement ce que
-- le garde-fou n°4 écarte.
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS location_lat        DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS location_lng        DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS location_expires_at TIMESTAMPTZ;

-- Les trois vont ensemble ou pas du tout : une latitude sans longitude ne
-- désigne rien, et un point sans échéance serait un partage qui ne finit pas.
ALTER TABLE messages
    ADD CONSTRAINT chk_messages_location_complete CHECK (
        (location_lat IS NULL AND location_lng IS NULL AND location_expires_at IS NULL)
        OR
        (location_lat IS NOT NULL AND location_lng IS NOT NULL AND location_expires_at IS NOT NULL)
    );

-- L'effacement des points échus balaie par l'échéance ; sans cet index il
-- parcourt toute la table des messages à chaque passage.
CREATE INDEX IF NOT EXISTS idx_messages_location_expires
    ON messages(location_expires_at)
    WHERE location_expires_at IS NOT NULL;

-- Ce que ce schéma ne fait pas, et pourquoi.
--
-- Il ne garde aucune trace d'un point après son échéance. La lecture refuse déjà
-- de servir un point échu — c'est elle qui fait foi — mais s'en tenir là
-- laisserait la base accumuler l'historique des positions de chacun, consultable
-- par quiconque a accès à la base. Un balayage efface les coordonnées échues ;
-- le message, lui, reste dans le fil et dit qu'une position a été partagée.
--
-- Il n'y a pas non plus de colonne « partage en cours » ni de dernière position
-- connue. Le partage est ponctuel : un point, capturé à l'envoi, qui ne se met
-- jamais à jour. Suivre quelqu'un supposerait de renvoyer un message à chaque
-- déplacement, ce qui est visible dans le fil — et c'est la propriété qu'on
-- cherche à garder.
