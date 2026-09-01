-- Le trajet aller : de quoi savoir si quelqu'un n'arrive jamais (priorité 5).
--
-- La boucle aller se compte depuis le début du créneau, pas depuis l'échéance de
-- retour. On fige donc le début de l'occurrence à l'armement, pour la même raison
-- que deadlineAt : le rollover d'un créneau récurrent réécrit starts_at toutes les
-- dix minutes, et une base recalculée à la lecture ferait fuir les demandes.

ALTER TABLE watches
    ADD COLUMN IF NOT EXISTS occurrence_starts_at TIMESTAMPTZ,
    -- La base des demandes « tu y es ? ». Égale au début de l'occurrence à
    -- l'armement, décalée de 15 min à chaque « je suis en chemin ».
    ADD COLUMN IF NOT EXISTS outbound_base_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS arrival_prompts_sent INTEGER NOT NULL DEFAULT 0;

-- Le balayage de la boucle aller : les veilles pas encore arrivées dont la base
-- est passée.
CREATE INDEX IF NOT EXISTS idx_watches_outbound
    ON watches(outbound_base_at)
    WHERE state IN ('ARMED', 'EN_ROUTE');
