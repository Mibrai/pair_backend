-- Langue des textes push, par appareil.
--
-- Une push s'affiche sur un téléphone verrouillé, avant tout code client : le
-- texte doit voyager dans la charge, dans la langue du DESTINATAIRE. Or la
-- traduction par Accept-Language ne vaut que pour la requête en cours — une
-- push est émise par la requête de quelqu'un d'autre, ou par un job planifié.
-- La langue est donc persistée là où on lit déjà les tokens au moment d'envoyer.
--
-- Par appareil et non par compte : un même utilisateur peut avoir un iPad en
-- anglais et un téléphone en allemand.
--
-- Nullable : un appareil enregistré avant cette colonne, ou sans langue connue,
-- reçoit le français — le même repli « en-tête absent » que le reste de l'API.
ALTER TABLE device_tokens ADD COLUMN locale VARCHAR(10);

COMMENT ON COLUMN device_tokens.locale IS
    'Étiquette de langue BCP 47 restreinte aux langues servies (fr, en, de). NULL = français.';
