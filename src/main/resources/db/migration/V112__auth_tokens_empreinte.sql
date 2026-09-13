-- P-BS-09 — la base ne garde plus qu'une empreinte des jetons d'e-mail.
--
-- Le défaut : `auth_tokens.token` portait le jeton en clair, celui-là même qui
-- figure dans le lien reçu par l'utilisateur. Une seule lecture de cette table
-- — une sauvegarde, un accès de lecture accordé à un outil de support, une
-- fuite d'identifiants de base — rendait utilisables tous les liens de
-- vérification et de réinitialisation en circulation, donc tous les comptes
-- correspondants : sans mot de passe, sans second facteur, et sans laisser la
-- moindre trace dans l'application. La fenêtre est courte (30 min pour une
-- réinitialisation, 24 h pour une vérification), mais elle est renouvelée à
-- chaque demande.
--
-- Le code condense désormais la valeur présentée par le lien et cherche par ce
-- condensat (`AuthToken.empreinte`, `findByTokenHashAndType`). La table ne
-- contient plus rien qui puisse être recopié dans une barre d'adresse.

-- ---------------------------------------------------------------- la colonne
--
-- VARCHAR(64) et non CHAR(64) comme le prévoyait la fiche. Deux raisons, dont
-- la première est décisive : le profil de test tourne en
-- `spring.jpa.hibernate.ddl-auto=validate`, et le validateur de schéma de
-- Hibernate compare le type de la colonne au type attendu pour un attribut
-- String, soit `varchar`. Face à un `bpchar` il refuse de démarrer le contexte,
-- et c'est toute la suite d'intégration qui tombe — pas seulement les tests de
-- cette fiche. La seconde : `bpchar` complète à la longueur fixe par des
-- espaces et compare sans en tenir compte, deux comportements dont un condensat
-- hexadécimal de longueur invariable n'a aucun besoin.
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);

-- ------------------------------------------------------------- le rattrapage
--
-- L'étape sans laquelle la livraison casserait tous les liens déjà envoyés.
-- Sans cet UPDATE, les lignes existantes n'auraient pas d'empreinte ; le code
-- neuf ne cherchant plus que par empreinte, chaque personne ayant reçu un lien
-- de vérification dans les 24 heures précédant le déploiement — et chaque
-- demande de réinitialisation des 30 dernières minutes — cliquerait sur « lien
-- inconnu », sans aucun moyen de distinguer ce cas d'un lien falsifié.
--
-- `sha256()` est native depuis PostgreSQL 11 ; l'image de test est en 16, et la
-- version de la base de production est à confirmer au runbook. `convert_to(…,
-- 'UTF8')` fixe l'encodage plutôt que de dépendre de celui du serveur, et
-- `encode(…, 'hex')` rend l'hexadécimal en minuscules : c'est exactement ce que
-- calcule `AuthToken.empreinte` en Java, et les deux doivent coïncider au
-- caractère près, sinon le rattrapage ne rattrape rien.
--
-- Idempotent (`WHERE token_hash IS NULL`) pour pouvoir être rejoué tel quel —
-- c'est ce que fait le test d'intégration qui insère une ligne « à l'ancienne »
-- puis rejoue cette instruction.
UPDATE auth_tokens
   SET token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex')
 WHERE token_hash IS NULL
   AND token IS NOT NULL;

-- ------------------------------------------------------------------- l'index
--
-- Unique, parce que la recherche du chemin critique est « retrouve-moi cette
-- empreinte » et que deux lignes ne peuvent pas partager la même : c'est
-- l'index qui remplace celui que la contrainte UNIQUE de `token` fournissait
-- depuis V79. Une collision SHA-256 sur des UUID v4 n'est pas un scénario ; une
-- double insertion du même jeton, si.
--
-- Pas de CONCURRENTLY : Flyway enveloppe la migration dans une transaction, où
-- `CREATE INDEX CONCURRENTLY` est interdit — voir l'en-tête de V107, et le test
-- `ForeignKeysIndexedIntegrationTest` qui refuse les index laissés invalides
-- par une construction concurrente interrompue. La table est petite (un jeton
-- par demande, purgée à l'échéance), le verrou est bref.
CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_tokens_token_hash
  ON auth_tokens (token_hash);

-- --------------------------------------------- la colonne en clair, en sursis
--
-- `token` perd son NOT NULL et rien d'autre. Elle continue d'être écrite par
-- `EmailVerificationService.emettre` : c'est la fenêtre de retour arrière. La
-- version précédente du code ne sait chercher que par la valeur en clair ;
-- tant qu'elle est écrite, y revenir ne perd aucun lien émis depuis le
-- déploiement.
--
-- Le NOT NULL est retiré maintenant, et non au Lot 4, pour la raison
-- symétrique : c'est ce qui permet au retour arrière d'insérer. Et c'est aussi
-- pourquoi `token_hash` reste NULLable ici — une version antérieure du code
-- n'écrirait pas d'empreinte, et un NOT NULL posé dès maintenant ferait échouer
-- chaque inscription et chaque demande de réinitialisation après un retour
-- arrière, c'est-à-dire exactement au moment où l'on a besoin que le service
-- fonctionne. La fiche prévoyait ce NOT NULL dès cette migration ; il est
-- déplacé au Lot 4, où il accompagnera la suppression de `token`.
ALTER TABLE auth_tokens ALTER COLUMN token DROP NOT NULL;

COMMENT ON COLUMN auth_tokens.token_hash IS
  'SHA-256 du jeton, hexadécimal minuscule (64 caractères). La seule colonne '
  'par laquelle le code retrouve un jeton depuis P-BS-09. Calculée en Java par '
  'AuthToken.empreinte, et par encode(sha256(convert_to(token,''UTF8'')),''hex'') '
  'pour le rattrapage des lignes antérieures.';

COMMENT ON COLUMN auth_tokens.token IS
  'Jeton en clair — EN SURSIS. Plus aucune lecture du code ne la compare ; elle '
  'reste écrite pour que le retour arrière du déploiement P-BS-09 reste sûr. '
  'Suppression prévue au Lot 4, au moins 7 jours après la livraison : un commit '
  'qui cesse de l''écrire, puis ALTER TABLE auth_tokens DROP COLUMN token et '
  'ALTER COLUMN token_hash SET NOT NULL.';
