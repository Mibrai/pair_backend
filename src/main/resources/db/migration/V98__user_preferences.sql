-- Les préférences privées d'une personne : une clé, une valeur opaque.
--
-- Écrit pour remplacer un stockage local qui ne survit pas à un changement
-- d'appareil, et pour éviter la forme qu'on nous demandait de NE PAS construire :
-- une relation d'amitié. Le raisonnement du client vaut d'être gardé ici, parce
-- que c'est lui qui explique la forme de cette table.
--
-- Une liste d'amis stockée devient interrogeable, exportable, et un écran finit
-- par afficher « vous n'êtes plus amis » — une notification que ce produit n'a
-- aucune raison d'héberger. Ne pas avoir la donnée est la seule garantie qui
-- tienne dans le temps.
--
-- Une valeur opaque appartenant à une seule personne ne peut pas devenir, par
-- inadvertance, une information sur quelqu'un d'autre : elle ne se joint à rien,
-- ne se cherche pas, et aucun écran ne peut la lire à l'envers. C'est la
-- différence, et elle n'est pas cosmétique. D'où :
--
--   * pas de colonne qui référence un autre utilisateur ;
--   * pas d'index sur la valeur — elle ne doit pas devenir interrogeable ;
--   * value en TEXT sans structure : le serveur ne l'interprète jamais.

CREATE TABLE IF NOT EXISTS user_preferences (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key        VARCHAR(64) NOT NULL,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, key),

    -- Une clé est un identifiant technique choisi par le client, pas une saisie
    -- d'utilisateur : on borne son alphabet pour qu'elle ne puisse pas transporter
    -- de contenu, ni ressembler à un chemin.
    CONSTRAINT user_preferences_cle_forme CHECK (key ~ '^[a-zA-Z0-9._-]{1,64}$'),

    -- Une borne franche plutôt qu'un TEXT libre : cet espace est un porte-clés de
    -- réglages, pas un stockage de documents.
    CONSTRAINT user_preferences_valeur_bornee CHECK (length(value) <= 8192)
);

-- La clé primaire (user_id, key) sert déjà les deux seules lectures : « la
-- préférence X de cette personne », et la cascade à la suppression du compte.
-- Rien d'autre à indexer, et surtout rien sur value.
