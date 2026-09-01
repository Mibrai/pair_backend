-- Les accusés de lecture du contact, datés sur la veille (retour chantier 01/09, §2).
--
-- Les deux boutons de la page publique — « j'ai vu », « je l'ai eue au téléphone »
-- — étaient jusqu'ici de simples événements de chronologie. On les remonte en
-- colonnes datées, pour que GET /watches/{id} puisse les rendre sans relire la
-- chronologie : c'est l'information la plus rassurante du module, quelqu'un a vu
-- et a réagi.

ALTER TABLE watches
    ADD COLUMN IF NOT EXISTS guardian_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS guardian_called_at TIMESTAMPTZ;
