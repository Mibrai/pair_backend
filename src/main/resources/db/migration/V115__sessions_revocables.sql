-- P-BS-03 — les sessions se révoquent.
--
-- Le défaut : le jeton de rafraîchissement était un JWT sans identifiant ni
-- stockage. Se déconnecter ne rendait rien inutilisable, et ni une
-- réinitialisation ni un changement de mot de passe ne coupaient les appareils
-- déjà connectés : un téléphone volé gardait trente jours d'accès, renouvelés.
--
-- Décisions : P-BS/D4 option B (rotation tolérante — un jeton déjà échangé reste
-- échangeable tant qu'aucun de ses successeurs n'a servi) et P-BS/D5 option A
-- (pas de plafond absolu : absolute_expires_at existe et reste nul).

-- La version des jetons d'un compte. Incrémentée quand un mot de passe change :
-- un jeton d'accès émis avant porte l'ancienne valeur, et le filtre le refuse dès
-- la requête suivante. Défaut 0 : les jetons en circulation, qui ne portent pas
-- de version, valent 0 et restent acceptés.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;

-- Une session = un appareil connecté. ON DELETE CASCADE : la purge RGPD d'un
-- compte emporte ses sessions (V111 exige un ON DELETE sur toute clé vers users).
CREATE TABLE IF NOT EXISTS refresh_sessions (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL,
    last_used_at        TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    revoked_reason      VARCHAR(30)
);

-- Un jeton de rafraîchissement émis, désigné par son jti. On stocke le jti et non
-- une empreinte du jeton : sans la clé de signature, un jti ne forge rien.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    jti         UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES refresh_sessions(id) ON DELETE CASCADE,
    parent_jti  UUID,
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);

-- Index simples et non partiels sur les clés étrangères : ForeignKeysIndexedIntegrationTest
-- (P-BA-08) ne compte pas un index partiel, qui ne sert pas au contrôle d'intégrité.
CREATE INDEX IF NOT EXISTS idx_refresh_sessions_user ON refresh_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session ON refresh_tokens(session_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_parent ON refresh_tokens(parent_jti);
