-- P-BS-02 étape 5 (P-BS/D2 option A) — les vingt comptes de démonstration ferment.
--
-- Les vingt comptes demo1 à demo20 du domaine pair.app ont été créés en production avec un mot de
-- passe commun publié dans le dépôt. Le relevé en lecture seule et le
-- déploiement du drapeau éteint (étapes 1 et 2 du runbook des comptes démo)
-- sont faits : l'ordre « drapeau éteint d'abord, désactivation ensuite » est
-- respecté, le seeder ne peut plus les recréer.
--
-- Réversible : rien n'est supprimé. is_active=false fait refuser connexion,
-- rafraîchissement et jetons d'accès ; une empreinte non bcrypt fait échouer
-- toute comparaison de mot de passe ; token_version incrémentée invalide les
-- jetons d'accès en circulation ; les sessions ouvertes sont révoquées. La purge
-- du contenu démo reste le Lot 2 (option B).
--
-- Production seulement. Flyway ne connaît pas les profils Spring : le
-- placeholder fermer_comptes_demo vaut false dans application.properties et true
-- sous railway et prod. En dev et en test, la migration s'enregistre sans rien
-- toucher — les comptes de démo du poste restent utilisables — et ne rejouera
-- jamais.

UPDATE refresh_sessions
   SET revoked_at = now(), revoked_reason = 'COMPTE_DEMO_FERME'
 WHERE revoked_at IS NULL
   AND user_id IN (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')
   AND '${fermer_comptes_demo}' = 'true';

UPDATE users
   SET is_active = false,
       password_hash = '!compte-demo-ferme',
       token_version = token_version + 1
 WHERE email LIKE 'demo%@pair.app'
   AND '${fermer_comptes_demo}' = 'true';
