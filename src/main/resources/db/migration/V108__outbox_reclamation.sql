-- La réclamation d'un message d'outbox sous verrou (P-BA-03, lot 1).
--
-- Ce que cette migration rend possible : un balayage prend un lot de messages en
-- posant sur chacun un état intermédiaire et une échéance de verrou, puis rend la
-- main — l'appel au fournisseur se fait hors transaction, hors connexion. Sans
-- état intermédiaire, deux instances qui balaient en même temps lisaient les
-- mêmes lignes PENDING et remettaient deux fois le même message au fournisseur.
--
-- locked_until : jusqu'à quand le balayage qui a réclamé le message a la main.
-- Passé ce délai, un autre balayage le reprend — c'est ce qui fait qu'un
-- conteneur tué en plein envoi ne laisse pas un message bloqué pour toujours. La
-- colonne est nullable et sans valeur par défaut : l'ALTER est instantané et ne
-- réécrit pas la table.
ALTER TABLE outbox_messages ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

-- Le vocabulaire de status accueille SENDING.
--
-- NOT VALID puis VALIDATE, et non un CHECK posé d'un bloc : la validation d'une
-- contrainte prend un verrou faible (SHARE UPDATE EXCLUSIVE) et laisse passer
-- lectures et écritures, alors qu'un ADD CONSTRAINT validant bloque la table le
-- temps de la relire. La table est petite — purgée à sept jours — mais la règle
-- vaut d'être appliquée là où elle ne coûte rien.
--
-- On garde FAILED comme état terminal plutôt que d'introduire un DEAD :
-- VerificationEmailDelivery.FAILED et le webhook de remise s'y réfèrent déjà, et
-- un second mot pour la même chose ferait deux vocabulaires à tenir d'accord.
ALTER TABLE outbox_messages DROP CONSTRAINT IF EXISTS outbox_status_vocabulaire;
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_status_vocabulaire
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')) NOT VALID;
ALTER TABLE outbox_messages VALIDATE CONSTRAINT outbox_status_vocabulaire;

-- Le second chemin de la réclamation : les messages dont le verrou a expiré,
-- dans le même ordre que le premier (priorité, puis ancienneté). L'index partiel
-- idx_outbox_a_envoyer de V87 ne couvre que les PENDING ; sans celui-ci, la
-- reprise d'un verrou expiré se ferait par balayage séquentiel.
CREATE INDEX IF NOT EXISTS idx_outbox_verrou_expire
    ON outbox_messages(priority, created_at)
    WHERE status = 'SENDING';

-- Retour arrière : avant de redéployer une image antérieure à ce lot, exécuter
--   UPDATE outbox_messages SET status = 'PENDING' WHERE status = 'SENDING';
-- Un conteneur ancien ne lit que PENDING : les messages laissés en SENDING par
-- le nouveau ne seraient jamais repris, et une alerte y resterait bloquée.
