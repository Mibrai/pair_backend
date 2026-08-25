-- « No enum constant NotificationFrequency.DAILY » — signalé par le client
-- mobile le 2026-08-25, observé trois fois en deux secondes en production.
--
-- Le client supposait des lignes héritées d'une version antérieure de
-- l'énumération. C'est plus embarrassant : la valeur vient de nos propres
-- migrations de semis. V12, V13 et V27 insèrent 'DAILY' dans
-- notification_prefs.frequency, alors que NotificationFrequency n'a jamais eu
-- que IMMEDIATE, DAILY_DIGEST et WEEKLY.
--
-- Le défaut n'a donc rien d'un résidu : ces migrations s'appliquent à toute base
-- neuve, et la reproduisent à chaque déploiement sur une base vierge.
--
-- Le symptôme est muet, et c'est ce qui le rend coûteux : l'envoi de
-- notifications est asynchrone, l'exception ne remonte à personne, et les
-- comptes touchés cessent simplement de recevoir leurs notifications. Rien ne
-- le signale hors des journaux du serveur.

UPDATE notification_prefs
   SET frequency = 'DAILY_DIGEST'
 WHERE frequency = 'DAILY';

-- V12, V13 et V27 ne sont pas modifiées. Elles sont déjà appliquées partout, et
-- réécrire une migration jouée est une habitude qui finit par coûter cher. Ce
-- n'est de toute façon pas nécessaire : Flyway applique dans l'ordre, donc sur
-- une base neuve cet UPDATE passe après les trois semis et les corrige aussi.

-- La contrainte, elle, empêche que le cas revienne — c'est-à-dire qu'une future
-- migration réintroduise une valeur que l'énumération Java ne connaît pas. Le
-- défaut n'est pas venu de l'application, qui ne peut écrire que des noms de
-- constantes ; il est venu du SQL, seul chemin où rien ne vérifiait rien.
--
-- NOT VALID délibérément : la contrainte s'applique aux écritures futures sans
-- contrôler les lignes existantes. Si une valeur inconnue subsistait quelque
-- part, une contrainte validée ferait échouer cette migration, et un échec de
-- migration empêche le service de démarrer. Bloquer la production pour se
-- protéger d'une donnée hypothétique serait un mauvais échange.
--
-- Pour la valider plus tard, une fois la table inspectée :
--   ALTER TABLE notification_prefs VALIDATE CONSTRAINT ck_notif_prefs_frequency;

ALTER TABLE notification_prefs
    DROP CONSTRAINT IF EXISTS ck_notif_prefs_frequency;

ALTER TABLE notification_prefs
    ADD CONSTRAINT ck_notif_prefs_frequency
    CHECK (frequency IN ('IMMEDIATE', 'DAILY_DIGEST', 'WEEKLY'))
    NOT VALID;
