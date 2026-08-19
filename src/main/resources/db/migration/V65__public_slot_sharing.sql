-- Pages publiques de créneau (lot B1 de meetdo-v2)
--
-- Un lien qu'on colle dans une conversation, et qui donne envie d'ouvrir
-- l'application. C'est le seul canal d'acquisition gratuit du produit, et il ne
-- vaut que par l'aperçu que les messageries en fabriquent.

ALTER TABLE schedules
    ADD COLUMN IF NOT EXISTS public_share_token VARCHAR(22) UNIQUE,
    ADD COLUMN IF NOT EXISTS is_publicly_shareable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS public_view_count INTEGER NOT NULL DEFAULT 0;

-- Pas d'index supplémentaire sur public_share_token : la contrainte UNIQUE
-- ci-dessus en crée déjà un, et c'est par lui que passe la seule recherche que
-- fait cette fonctionnalité. Un second index sur la même colonne coûterait des
-- écritures sans rien accélérer.

-- Aucun rétro-remplissage, contrairement à ce que la spécification prévoyait.
--
-- Générer 43 jetons ici demanderait de le faire en SQL, or pgcrypto n'est pas
-- installé — V1 n'active que uuid-ossp, postgis et vector. Le contournement
-- naturel, un md5 tronqué à 22 caractères, ne vaudrait que 88 bits là où le
-- générateur applicatif en produit 131, et laisserait deux qualités de jeton
-- coexister dans la même colonne sans que rien ne le signale.
--
-- Le jeton est donc créé à la première demande de lien, par ShareToken. Un
-- créneau que personne n'a jamais partagé n'a pas besoin d'adresse publique, et
-- celui qu'on partage en obtient une immédiatement. La colonne reste nulle en
-- attendant, ce qui a un mérite second : elle dit exactement quels créneaux ont
-- été partagés au moins une fois.
