-- P-BA-18 option B / P-BL-20 (décision du 13/09) — plus de fréquence de résumé.
--
-- données : aucun résumé quotidien ou hebdomadaire n'a jamais été envoyé. Une
-- préférence DAILY_DIGEST ou WEEKLY ne faisait qu'avaler les e-mails de son type
-- (V80 en a même converti depuis le seed). Elles redeviennent IMMEDIATE, ce que
-- le serveur applique désormais de toute façon. Sans perte : la valeur n'avait
-- aucun effet voulu. La contrainte ck_notif_prefs_frequency reste, les deux
-- valeurs étant encore acceptées en entrée.

UPDATE notification_prefs
   SET frequency = 'IMMEDIATE'
 WHERE frequency IN ('DAILY_DIGEST', 'WEEKLY');
