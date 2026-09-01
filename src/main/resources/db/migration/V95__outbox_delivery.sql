-- L'état de remise rapporté par le fournisseur (webhook Resend).
--
-- Un axe distinct de status : status dit « accepté par le fournisseur », delivery
-- dit « arrivé, rebondi, ou en attente ». Avec un seul canal — l'e-mail — c'est ce
-- second axe qui manquait pour distinguer un envoi accepté d'un message reçu.

ALTER TABLE outbox_messages
    ADD COLUMN IF NOT EXISTS delivery_state VARCHAR(12) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE outbox_messages
    ADD CONSTRAINT outbox_delivery_vocabulaire
    CHECK (delivery_state IN ('UNKNOWN', 'DELIVERED', 'DELAYED', 'BOUNCED', 'COMPLAINED'));
