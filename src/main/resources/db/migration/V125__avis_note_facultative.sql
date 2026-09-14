-- P-BL-10 (décision D4 du 13/09), étapes A et C du module avis — la note d'un avis n'est plus exigée.
--
-- Le serveur n'écrit plus de note : les nouveaux avis portent score à NULL. La
-- colonne était NOT NULL, avec un CHECK entre 1 et 5 qui laisse passer NULL et
-- reste donc en place pour les notes déjà saisies.
--
-- Les notes existantes ne sont ni effacées ni lues : l'étape D les supprime avec
-- la colonne, deux semaines après ce déploiement (réponse de l'app du 14/09).
-- Réversible tant que D n'a pas eu lieu, à condition de ne pas remettre NOT NULL
-- sur des lignes écrites depuis.

ALTER TABLE reviews ALTER COLUMN score DROP NOT NULL;
