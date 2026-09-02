-- Le rôle d'un contact d'urgence : principal, secours, ou aucun.
--
-- Jusqu'ici rien ne distinguait un contact d'un autre, et la feuille d'armement
-- du client retombait sur « le premier contact accepté de la liste » — un ordre
-- qui vient de nous, qui n'a aucun sens pour la personne, et qui peut changer
-- entre deux ouvertures. Quelqu'un avec trois contacts acceptés armait donc au
-- profit de l'un d'eux sans le savoir.
--
-- NULL vaut « aucun rôle ». C'est l'état de la grande majorité des lignes, et le
-- représenter par l'absence plutôt que par un mot évite d'avoir à rétro-remplir.

ALTER TABLE guardians ADD COLUMN IF NOT EXISTS role VARCHAR(8);

ALTER TABLE guardians DROP CONSTRAINT IF EXISTS guardians_role_vocabulaire;
ALTER TABLE guardians ADD CONSTRAINT guardians_role_vocabulaire
    CHECK (role IS NULL OR role IN ('PRIMARY', 'BACKUP'));

-- Au plus un principal et au plus un secours par personne, tenus par la base.
--
-- L'app le garantit déjà de son côté — poser un rôle le retire de l'autre — mais
-- deux appareils connectés au même compte peuvent poser deux principaux sans
-- jamais se croiser. Un invariant que seul le client tient n'est pas un
-- invariant : il ne survit pas au second client.
CREATE UNIQUE INDEX IF NOT EXISTS uq_guardians_un_principal
    ON guardians (owner_id) WHERE role = 'PRIMARY';

CREATE UNIQUE INDEX IF NOT EXISTS uq_guardians_un_secours
    ON guardians (owner_id) WHERE role = 'BACKUP';

-- Un contact ne peut pas être les deux à la fois : c'est structurel ici, une
-- colonne unique ne portant qu'une valeur. Le cas « principal = secours » que le
-- client redoutait à l'armement est refusé séparément, dans WatchService.arm.

-- Le rôle ne survit pas au refus : un contact qui a dit non ne peut pas rester
-- principal. Rattrapage des lignes déjà en base — il ne devrait y en avoir
-- aucune, la colonne vient de naître, mais la règle vaut d'être écrite ici aussi.
UPDATE guardians SET role = NULL WHERE consent_state = 'REFUSED' AND role IS NOT NULL;
