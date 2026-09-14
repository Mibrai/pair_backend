-- P-BA-04 lot 2 (décision D4 option B) : un verrou en base par job planifié.
--
-- La table est celle qu'attend ShedLock (JdbcTemplateLockProvider). Une ligne par
-- nom de verrou ; lock_until dit jusqu'à quand l'instance qui l'a pris le garde,
-- même si elle meurt en cours de route. Horodatages en TIMESTAMPTZ et lus à
-- l'heure de la base (usingDbTime) : deux conteneurs aux horloges décalées
-- comparent la même heure.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
