-- Pages publiques de programme (demande mobile du 2026-08-20)
--
-- Le partage public a été construit pour les créneaux, et pour eux seuls (V65).
-- Un programme partagé arrivait donc chez son destinataire sous la forme
-- « meetdo://programs/42 » : aucune messagerie ne rend cliquable un schéma
-- propriétaire, et le lien était une chaîne à recopier à la main — sans effet
-- pour qui n'a pas encore l'application.
--
-- Les trois colonnes de V65, transposées telles quelles. Rien à réinventer : le
-- contrat des créneaux fonctionne, et deux mécaniques de partage divergentes
-- pour un même produit finiraient par ne plus se ressembler.

ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS public_share_token VARCHAR(22) UNIQUE,
    ADD COLUMN IF NOT EXISTS is_publicly_shareable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS public_view_count INTEGER NOT NULL DEFAULT 0;

-- Pas d'index supplémentaire : la contrainte UNIQUE en crée déjà un, et c'est
-- par lui que passe la seule recherche de cette fonctionnalité.

-- Aucun rétro-remplissage, pour la raison exacte de V65 : pgcrypto n'est pas
-- installé, et un md5 tronqué ne vaudrait que 88 bits là où ShareToken en produit
-- 131 — deux qualités de jeton coexisteraient dans la même colonne sans que rien
-- ne le signale. Le jeton est créé à la première demande de lien.
--
-- La colonne nulle a un mérite second, déjà relevé pour les créneaux : elle dit
-- exactement quels programmes ont été partagés au moins une fois.
