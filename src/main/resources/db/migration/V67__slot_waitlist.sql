-- Liste d'attente (lot C1 de meetdo-v2)
--
-- Un créneau complet renvoyait un refus sec, sans rien retenir de la personne
-- qui voulait venir. La file garde son rang, et la place qui se libère lui
-- revient.

ALTER TABLE slot_participations
    ADD COLUMN IF NOT EXISTS waitlist_position INTEGER,
    ADD COLUMN IF NOT EXISTS promoted_at TIMESTAMPTZ,

    -- Non prévue par la spécification, et indispensable ici comme au lot C4.
    --
    -- La table ne porte que created_at : rien ne date un désistement. Impossible
    -- donc de distinguer quelqu'un qui se retire trois jours avant de quelqu'un
    -- qui se retire une heure avant, alors que c'est exactement la distinction
    -- que C4 doit faire. La colonne est ajoutée maintenant parce que C1 touche
    -- déjà cette table et écrit déjà des désistements : l'ajouter plus tard
    -- laisserait un trou dans les données entre les deux lots.
    ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMPTZ;

-- Deux personnes ne peuvent pas occuper le même rang.
--
-- Le verrou pessimiste sur la ligne du créneau protège les chemins qui passent
-- par lui ; cet index protège des autres. Partiel, parce que le rang n'a de sens
-- que pour une personne en attente : une fois promue ou retirée, sa position ne
-- vaut plus rien et deux anciens deuxièmes ne se gênent pas.
CREATE UNIQUE INDEX IF NOT EXISTS uq_waitlist_position
    ON slot_participations (schedule_id, waitlist_position)
    WHERE status = 'WAITLISTED';
