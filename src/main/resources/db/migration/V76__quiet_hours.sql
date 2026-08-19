-- Heures de silence (lot D6 de meetdo-v2)
--
-- « Ne me réveille pas. » Deux entiers, et la fenêtre qu'ils décrivent peut
-- traverser minuit — c'est même le cas normal.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS quiet_hours_start SMALLINT,
    ADD COLUMN IF NOT EXISTS quiet_hours_end   SMALLINT;

-- Les deux vont ensemble ou pas du tout : une heure de début sans fin ne décrit
-- aucune fenêtre, et laisser passer la moitié d'un réglage produirait un silence
-- qui ne s'arrête jamais.
ALTER TABLE users
    ADD CONSTRAINT chk_users_quiet_hours CHECK (
        (quiet_hours_start IS NULL AND quiet_hours_end IS NULL)
        OR (
            quiet_hours_start BETWEEN 0 AND 23
            AND quiet_hours_end BETWEEN 0 AND 23
            AND quiet_hours_start <> quiet_hours_end
        )
    );

-- Aucun remplissage : NULL veut dire « pas de silence demandé », et c'est vrai
-- de tout le parc. Deviner « 22 h – 7 h pour tout le monde » aurait fait taire
-- des notifications que personne n'avait demandé de faire taire.

-- Ce que ces deux colonnes ne disent pas, et où c'est décidé.
--
-- Elles ne portent pas de fuseau. Le fuseau est celui de l'APPAREIL, que
-- device_tokens.timezone porte depuis V56 : quelqu'un qui a un téléphone à Paris
-- et une tablette restée à Tokyo n'a pas les mêmes heures creuses sur les deux,
-- et un fuseau unique par compte aurait forcé à en choisir un arbitrairement. Le
-- filtrage est donc appliqué appareil par appareil, avant le groupement par
-- langue et par fuseau qui existait déjà.
--
-- Elles ne disent pas non plus quelles notifications passent outre. C'est
-- NotificationType.isCritical() qui tranche, et cette liste-là est du code parce
-- qu'elle relève d'un jugement — « information indispensable » contre
-- « engagement » — et non d'un réglage.
--
-- Enfin, un silence ne perd rien : la notification in-app est écrite dans tous
-- les cas, comme pour une conversation en sourdine. Ce qui est coupé, c'est la
-- push, c'est-à-dire le fait de sonner.
