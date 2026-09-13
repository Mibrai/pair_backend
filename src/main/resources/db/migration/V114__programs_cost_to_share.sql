-- P-MU-16 (décision D7, option C) — « Frais à prévoir » : un booléen et une
-- précision, jamais un prix.
--
-- Le défaut : aucun champ de coût n'existait. Un participant découvrait sur place
-- qu'il fallait payer le terrain ou la salle.
--
-- Pourquoi pas un montant : la doctrine refuse les classements. Un prix devient
-- vite un tri par prix, et un « 0 € » par défaut une promesse. L'organisateur
-- coche lui-même, et précise s'il le veut. Aucun index : ces colonnes ne servent
-- ni à trier ni à filtrer, et un index serait la première pierre de l'un ou
-- l'autre.
--
-- Le défaut à FALSE ne veut pas dire « gratuit » : il veut dire que
-- l'organisateur n'a rien annoncé. Le client n'affiche jamais « Gratuit » par
-- déduction.
ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS cost_to_share BOOLEAN NOT NULL DEFAULT FALSE;

-- Libre, courte, et nulle dès que cost_to_share est faux : c'est le service qui
-- la remet à NULL, pour qu'une précision ne survive pas à l'annonce qu'elle
-- précisait.
ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS cost_note VARCHAR(80);
