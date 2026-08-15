-- Fuseau horaire de l'appareil, pour composer les heures des pushes Android.
--
-- Android n'a aucune extension qui réécrive la bannière sur l'appareil : le
-- texte composé par le serveur est tout ce que l'utilisateur verra, et écrire
-- « 19:00 » exige un fuseau. Nous n'en avions aucun — device_tokens portait la
-- langue de l'appareil mais pas son fuseau, et rien dans le créneau ne dit celui
-- de son lieu. Le formatage se faisait donc dans le fuseau de référence de
-- l'application (pair.push.zone), exact pour la France et l'Allemagne qui
-- partagent le décalage, faux d'une heure pour un appareil réglé à Londres.
--
-- Étiquette IANA (« Europe/Paris »), jamais un décalage. « +02:00 » décrit un
-- instant, pas une règle : un rappel émis fin octobre pour une séance de
-- novembre serait décalé d'une heure. L'étiquette porte le changement d'heure
-- avec elle.
--
-- 64 caractères : la plus longue étiquette de la base IANA en fait 32
-- (« America/Argentina/ComodRivadavia »), le double laisse la place aux ajouts
-- sans qu'une troncature silencieuse produise un fuseau invalide.
ALTER TABLE device_tokens ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);

COMMENT ON COLUMN device_tokens.timezone IS
  'Fuseau de l''appareil, étiquette IANA (Europe/Paris). NULL = la plateforme ne '
  'sait pas répondre, ou jeton enregistré avant cette colonne : le formatage '
  'retombe alors sur pair.push.zone.';

-- Aucun remplissage : NULL est la valeur juste pour un jeton enregistré avant
-- que le client n'envoie le champ. Deviner « Europe/Paris » pour tout le monde
-- donnerait la bonne heure aux mêmes appareils qu'aujourd'hui, mais rendrait
-- l'inconnu indiscernable du déclaré — et le premier ré-enregistrement pose la
-- vraie valeur de toute façon.
