-- L'outbox des messages d'alerte (priorité 4 du lot traçabilité).
--
-- Un message est écrit ici dans la même transaction que la décision qui le
-- produit — l'escalade, la levée — puis repris par un balayage qui l'envoie. En
-- base et non en mémoire : un pool en mémoire perdrait ses envois en attente à
-- chaque redéploiement, précisément le mode d'échec que « le serveur tient les
-- minuteurs » existe pour fermer.

CREATE TABLE IF NOT EXISTS outbox_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel             VARCHAR(8) NOT NULL,
    recipient           VARCHAR(255) NOT NULL,
    subject             VARCHAR(200),
    body                TEXT NOT NULL,

    -- Plus le nombre est petit, plus c'est prioritaire. C'est ce qui fait la
    -- « file dédiée » : une alerte passe devant un e-mail de version longue.
    priority            INTEGER NOT NULL DEFAULT 0,

    status              VARCHAR(8) NOT NULL DEFAULT 'PENDING',
    attempts            INTEGER NOT NULL DEFAULT 0,

    watch_id            UUID REFERENCES watches(id) ON DELETE SET NULL,
    provider_message_id VARCHAR(128),

    last_attempt_at     TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT outbox_channel_vocabulaire CHECK (channel IN ('SMS', 'EMAIL')),
    CONSTRAINT outbox_status_vocabulaire CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- Le chemin chaud du balayage : les messages à envoyer, du plus prioritaire au
-- plus ancien. L'index ne couvre que les PENDING — les autres ne se rebalaient pas.
CREATE INDEX IF NOT EXISTS idx_outbox_a_envoyer
    ON outbox_messages(priority, created_at)
    WHERE status = 'PENDING';

-- Recouper un accusé de remise avec le message qui l'a produit.
CREATE INDEX IF NOT EXISTS idx_outbox_provider_msg
    ON outbox_messages(provider_message_id)
    WHERE provider_message_id IS NOT NULL;
