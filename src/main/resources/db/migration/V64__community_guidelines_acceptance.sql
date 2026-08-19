-- Acceptation des règles de communauté (lot A5 de meetdo-v2) — bloquant stores
--
-- Deux colonnes : quand la personne a accepté, et quelle version elle a acceptée.
-- La seconde est ce qui rend la première utile — sans elle, on saurait qu'une
-- acceptation a eu lieu sans savoir sur quoi elle portait, et une modification
-- substantielle du texte ne pourrait pas être redemandée.
--
-- guidelines_version est un VARCHAR court et non un numéro : la comparaison est
-- une égalité, jamais un ordre. « La version acceptée est-elle celle en
-- vigueur ? » est la seule question posée, et elle ne demande pas de savoir si
-- 1.10 vient après 1.9.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS guidelines_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS guidelines_version VARCHAR(10);

-- Aucun rétro-remplissage, à l'inverse de V60.
--
-- L'onboarding rétro-remplissait parce que les comptes existants l'avaient de
-- fait déjà traversé. Ici c'est l'inverse : personne n'a jamais vu ces règles,
-- et prétendre qu'elles ont été acceptées viderait la fonctionnalité de son
-- sens — c'est précisément l'acceptation explicite que les stores demandent.
-- Tout le parc sera donc sollicité une fois, ce qui est voulu.
