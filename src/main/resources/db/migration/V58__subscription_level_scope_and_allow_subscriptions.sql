-- Lot A des abonnements — voir docs/specs/REPONSE_BACKEND_ABONNEMENTS_2026-08.md.
--
-- Trois ajouts en une seule migration, dont deux ne servent qu'au lot B : la
-- table `subscriptions` est en production, et deux ALTER sur la même table à
-- quelques jours d'intervalle se paient sans s'expliquer.
--
-- Aucune reprise de données : les trois valeurs par défaut préservent
-- exactement le comportement actuel.
--
-- Numérotée V58 et non V57 : la V57 est prise par le chantier « identité
-- d'occurrence » des cartes-souvenirs, encore sur sa branche. Flyway n'exige
-- pas la contiguïté, il exige l'unicité.

-- 1. Le niveau, par abonnement (§2.1).
--    ALL par défaut : une ligne existante notifie comme avant.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS level VARCHAR(20) NOT NULL DEFAULT 'ALL';

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS chk_subscription_level;
ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_level
    CHECK (level IN ('ALL', 'NEW_ONLY', 'MUTED'));

-- 2. La portée géographique d'un abonnement CATEGORY (§2.2).
--    Unité : le MÈTRE, comme /search et /slots/feed.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS lat           DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS lng           DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS radius_meters INTEGER;

-- Les trois champs valent ensemble ou pas du tout, et seul un abonnement
-- CATEGORY peut les porter : un rayon sur un abonnement AUTHOR n'aurait aucun
-- effet, et une colonne qui accepte une valeur sans effet finit par en recevoir
-- une — puis par être lue comme si elle en avait un.
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS chk_subscription_scope;
ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_scope CHECK (
        (lat IS NULL AND lng IS NULL AND radius_meters IS NULL)
        OR (type = 'CATEGORY'
            AND lat IS NOT NULL AND lng IS NOT NULL AND radius_meters IS NOT NULL
            AND lat BETWEEN -90 AND 90
            AND lng BETWEEN -180 AND 180
            AND radius_meters BETWEEN 1 AND 200000)
    );

-- 3. « Qui peut me suivre » (§2.5), à côté de allow_messages dont il est le
--    jumeau : même écran, même geste, même forme.
--
--    OPEN par défaut, et le réglage ne porte que sur les abonnements À VENIR :
--    passer à NOBODY ferme la porte, il ne vide pas la pièce. Les lignes
--    existantes restent et continuent de notifier — voir le §5.2 de la réponse
--    client, et le libellé du réglage qui l'annonce.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS allow_subscriptions VARCHAR(20) NOT NULL DEFAULT 'OPEN';

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_allow_subscriptions;
ALTER TABLE users
    ADD CONSTRAINT chk_users_allow_subscriptions
    CHECK (allow_subscriptions IN ('OPEN', 'NOBODY'));
