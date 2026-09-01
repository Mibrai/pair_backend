-- Les veilles retour et leur chronologie (priorité 2 du lot traçabilité).
--
-- Une veille est armée par un utilisateur sur un créneau, avec un contact
-- principal (et un contact de secours facultatif). Le serveur en tient l'état et
-- l'échéance ; l'application n'en planifie aucun minuteur.

CREATE TABLE IF NOT EXISTS watches (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id         UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    state               VARCHAR(12) NOT NULL DEFAULT 'ARMED',
    armed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    arrival_confirmed_at TIMESTAMPTZ,
    interrupted_at      TIMESTAMPTZ,

    -- L'échéance, figée à l'armement et jamais redérivée du créneau. Un créneau
    -- récurrent voit son starts_at réécrit toutes les dix minutes ; une échéance
    -- recalculée à la lecture fuirait devant elle. Elle n'est déplacée que par un
    -- snooze ou une interruption, gestes des priorités suivantes.
    deadline_at         TIMESTAMPTZ NOT NULL,

    reminders_sent      INTEGER NOT NULL DEFAULT 0,

    -- Les contacts sont des guardians ACCEPTED de l'utilisateur. On ne pose PAS de
    -- clé étrangère vers guardians : un contact retiré plus tard ne doit pas
    -- effacer l'historique d'une veille close, et le lien vaut au moment de
    -- l'armement, vérifié alors par le service.
    guardian_id         UUID NOT NULL,
    backup_guardian_id  UUID,

    closed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT watches_state_vocabulaire CHECK (state IN (
        'ARMED', 'EN_ROUTE', 'ON_SITE', 'REMINDING', 'ESCALATED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT watches_reminders_bornes CHECK (reminders_sent BETWEEN 0 AND 3)
);

-- Le chemin chaud : « mes veilles actives », et le balayage des minuteurs sur les
-- veilles non closes dont l'échéance approche.
CREATE INDEX IF NOT EXISTS idx_watches_user_state ON watches(user_id, state);
CREATE INDEX IF NOT EXISTS idx_watches_deadline ON watches(deadline_at) WHERE state <> 'CLOSED';

-- Une seule veille vivante par créneau et par personne : en armer une seconde
-- pendant que la première tourne dédoublerait alertes et rappels. Les états
-- terminaux (RESOLVED, CLOSED) ne comptent pas — on peut réarmer après coup.
CREATE UNIQUE INDEX IF NOT EXISTS uq_watches_active_par_creneau
    ON watches(user_id, schedule_id)
    WHERE state NOT IN ('RESOLVED', 'CLOSED');


-- La chronologie : les faits qui jalonnent une veille, dans l'ordre.
CREATE TABLE IF NOT EXISTS watch_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    watch_id    UUID NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    detail      VARCHAR(200)
);

-- La seule lecture : la chronologie d'une veille, dans l'ordre chronologique.
CREATE INDEX IF NOT EXISTS idx_watch_events_watch ON watch_events(watch_id, occurred_at);
