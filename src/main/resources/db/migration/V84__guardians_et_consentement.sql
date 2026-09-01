-- Contacts d'urgence du module de veille (priorité 1 du lot traçabilité).
--
-- Deux tables. « guardians » porte les contacts qu'un utilisateur désigne, avec
-- leur état de consentement. « refused_contacts » est la liste, globale à tout
-- meetDo, des numéros ayant refusé d'être sollicités — séparée à dessein, parce
-- qu'elle ne concerne personne en particulier : c'est un fait attaché à un
-- numéro, pas une relation entre deux comptes.

CREATE TABLE IF NOT EXISTS guardians (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Un contact est SOIT un membre meetDo (member_id), SOIT un contact externe
    -- (name/phone/email). Le CHECK ci-dessous interdit les lignes qui seraient les
    -- deux à la fois ou ni l'un ni l'autre — un contact sans moyen d'être joint
    -- n'est pas un contact.
    member_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(120),
    phone         VARCHAR(20),   -- E.164 normalisé, jamais la saisie brute
    email         VARCHAR(255),

    consent_state VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    consent_token VARCHAR(22) NOT NULL UNIQUE,
    invited_at    TIMESTAMPTZ,
    responded_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Pas d'adresse postale : rien dans ce flux n'envoie de courrier, et c'est une
    -- donnée sensible de tiers sans usage. Son absence de la table est la garantie
    -- que personne ne la remplira par mégarde.

    CONSTRAINT guardians_consent_state_vocabulaire
        CHECK (consent_state IN ('PENDING', 'ACCEPTED', 'REFUSED')),

    -- Membre XOR externe : exactement l'un des deux. Un membre ne porte pas de
    -- coordonnées en clair (on le joint dans l'app) ; un externe porte au moins un
    -- canal (téléphone ou e-mail), sans quoi le message ① ne saurait où aller.
    CONSTRAINT guardians_membre_ou_externe CHECK (
        (member_id IS NOT NULL AND phone IS NULL AND email IS NULL)
     OR (member_id IS NULL AND (phone IS NOT NULL OR email IS NOT NULL))
    )
);

-- Le chemin chaud du module : « les contacts acceptés de cet utilisateur »,
-- lu à chaque tentative d'armement.
CREATE INDEX IF NOT EXISTS idx_guardians_owner ON guardians(owner_id);

-- La lecture du flux public de consentement : retrouver le contact par son jeton.
-- L'unicité de consent_token crée déjà cet index ; rien à ajouter.

-- Un même parrain ne redésigne pas deux fois le même membre : ce serait deux
-- lignes disant la même chose, et la seconde invitation partirait pour rien.
CREATE UNIQUE INDEX IF NOT EXISTS uq_guardians_owner_member
    ON guardians(owner_id, member_id) WHERE member_id IS NOT NULL;


-- La liste des numéros refusés. Ni le numéro, ni qui l'a refusé, ni qui l'avait
-- désigné : seulement l'empreinte HMAC du numéro E.164 sous le poivre, et la
-- version de clé qui l'a produite.
CREATE TABLE IF NOT EXISTS refused_contacts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_hash  VARCHAR(64) NOT NULL UNIQUE,   -- HMAC-SHA256 en hexadécimal
    key_version INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- L'unique lecture : « ce numéro est-il refusé ? », par égalité sur l'empreinte.
-- La contrainte UNIQUE sur phone_hash fournit déjà l'index.
