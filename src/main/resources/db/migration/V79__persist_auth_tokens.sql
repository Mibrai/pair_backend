-- Persister les jetons de vérification d'e-mail et de réinitialisation
-- (signalé par le client mobile, PROMPT_BACKEND_VERIFICATION_EMAIL_2026-08-25)
--
-- Le ticket portait sur un lien d'e-mail pointant vers localhost:3000. En
-- corrigeant l'URL, on découvre que la cible n'aurait pas suffi : les jetons
-- vivaient dans deux `ConcurrentHashMap` de `EmailVerificationService`, donc
-- dans la mémoire du conteneur. Tout redéploiement les effaçait, et le testeur
-- qui cliquait ensuite recevait « jeton invalide » — indiscernable, pour lui,
-- d'un lien cassé.
--
-- Ce défaut était invisible de l'extérieur et invisible en développement, où
-- l'on ne redéploie pas entre l'inscription et le clic. Il ne l'est plus dès
-- qu'on livre : le déplacement de base prévu redémarre le service, et aurait
-- invalidé d'un coup tous les liens en circulation.
--
-- Une seule table pour les deux usages : ce sont le même objet (un secret à
-- usage unique, porteur d'un utilisateur et d'une échéance) et ils avaient déjà
-- le même défaut. Deux tables jumelles auraient divergé.

CREATE TABLE IF NOT EXISTS auth_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token        VARCHAR(255) NOT NULL UNIQUE,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type         VARCHAR(30) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- La contrainte UNIQUE sur `token` crée déjà l'index de la seule lecture du
-- chemin critique (retrouver un jeton présenté par un navigateur).

-- Celui-ci sert au renvoi : « cet utilisateur a-t-il un jeton encore valide ? »
CREATE INDEX IF NOT EXISTS idx_auth_tokens_user_type ON auth_tokens(user_id, type);

-- Et celui-ci à la purge des jetons échus.
CREATE INDEX IF NOT EXISTS idx_auth_tokens_expires ON auth_tokens(expires_at);

-- `consumed_at` plutôt qu'une suppression : un jeton déjà utilisé doit pouvoir
-- être distingué d'un jeton inconnu. C'est la différence entre « votre compte
-- est déjà vérifié, vous pouvez fermer cette page » et « ce lien n'existe
-- pas » — deux messages que le ticket demande explicitement de ne pas
-- confondre, et qu'une ligne effacée rendrait identiques.
--
-- Aucune reprise de l'existant : les jetons en mémoire au moment du déploiement
-- sont perdus de toute façon, puisque c'est précisément le défaut corrigé. Les
-- comptes non vérifiés redemanderont un lien.
