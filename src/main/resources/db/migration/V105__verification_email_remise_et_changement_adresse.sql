-- Lot « e-mail de vérification » du 07/09/2026.
--
-- Trois trous se referment ensemble, et ils n'en font qu'un. L'e-mail de
-- vérification était le seul de nos courriers à ne pas passer par l'outbox :
-- donc le seul dont l'identifiant Resend était jeté (ResendEmailService:51),
-- donc le seul dont l'accusé de remise — écrit le 1er septembre, signé, routé —
-- ne pouvait rien dire. Un rebond arrivait, ne trouvait aucune ligne à
-- recouper, et repartait en silence.
--
-- 1. outbox_messages apprend à porter un compte et un usage, pour qu'un accusé
--    de remise sache à qui il se rapporte.
-- 2. users porte l'état du dernier envoi, et l'identifiant du message concerné.
--    Cet identifiant n'est pas un doublon de celui de l'outbox : c'est lui qui
--    empêche un rebond tardif du premier renvoi d'écraser la remise du second,
--    et il survit à la purge de l'outbox (7 jours) alors que l'état, lui, doit
--    rester lisible sur le compte.
-- 3. users porte une adresse en attente. Sans elle, l'état BOUNCED que ce lot
--    rend visible serait un diagnostic sans remède : l'adresse d'un compte
--    n'était modifiable nulle part — ni dans UpdateProfileRequest, ni par une
--    route dédiée — et une faute de frappe à l'inscription était définitive.

-- ------------------------------------------------------------------ outbox

ALTER TABLE outbox_messages
    ADD COLUMN IF NOT EXISTS user_id UUID;

ALTER TABLE outbox_messages
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(20) NOT NULL DEFAULT 'WATCH_ALERT';

-- Le défaut vaut rétro-remplissage : tout ce que l'outbox portait jusqu'ici
-- était une alerte de veille, et rien d'autre n'y avait accès.
ALTER TABLE outbox_messages
    ADD CONSTRAINT outbox_purpose_vocabulaire
    CHECK (purpose IN ('WATCH_ALERT', 'EMAIL_VERIFICATION'));

-- Un message de vérification porte un compte ; une alerte de veille n'en porte
-- pas (son destinataire est un proche, qui n'a pas forcément de compte meetDo).
ALTER TABLE outbox_messages
    ADD CONSTRAINT outbox_verification_porte_un_compte
    CHECK (purpose <> 'EMAIL_VERIFICATION' OR user_id IS NOT NULL);

-- ------------------------------------------------------------------- users

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS verification_email_delivery VARCHAR(12) NOT NULL DEFAULT 'NONE';

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS verification_email_message_id VARCHAR(128);

ALTER TABLE users
    ADD CONSTRAINT users_verification_delivery_vocabulaire
    CHECK (verification_email_delivery IN
           ('NONE', 'PENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED'));

-- L'adresse demandée par un changement, tant que le lien n'a pas été cliqué.
-- Ce n'est PAS l'adresse du compte : la bascule se fait au clic, et jamais
-- avant. Une seconde faute de frappe ne doit pas enfermer quelqu'un dehors,
-- puisque l'adresse est aussi l'identifiant de connexion.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);

-- Unicité de l'adresse en attente : deux comptes ne peuvent pas convoiter la
-- même adresse. Partielle, parce que la valeur est nulle la plupart du temps.
-- Elle ne remplace pas la vérification faite au clic — entre la demande et le
-- clic, quelqu'un peut avoir inscrit cette adresse pour de bon.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_pending_email
    ON users (pending_email) WHERE pending_email IS NOT NULL;
