-- Le code de retour d'une veille (priorité 3 du lot traçabilité).
--
-- Le secret qui lève une veille, connu de la seule personne qui l'a créé. La
-- table ne porte jamais le code en clair : seulement son empreinte HMAC sous le
-- poivre, avec le sel propre au code et la version de clé. Une pour une avec la
-- veille ; supprimée à la clôture, jamais marquée obsolète.

CREATE TABLE IF NOT EXISTS return_codes (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Une pour une avec la veille. La contrainte UNIQUE l'impose, et le
    -- ON DELETE CASCADE fait disparaître le code si la veille est effacée — un
    -- secret n'a aucune raison de survivre à ce qu'il protégeait.
    watch_id      UUID NOT NULL UNIQUE REFERENCES watches(id) ON DELETE CASCADE,

    hash          VARCHAR(64) NOT NULL,   -- HMAC-SHA256(sel || code), hexadécimal
    salt          VARCHAR(32) NOT NULL,   -- sel propre au code, base64
    key_version   INTEGER NOT NULL,
    attempts_left INTEGER NOT NULL DEFAULT 3,

    -- Le code de contrainte, s'il existe : présenté à la clôture, il répond comme
    -- un succès et déclenche l'escalade en silence. Même sel et même version de
    -- clé que le code normal.
    duress_hash   VARCHAR(64),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT return_codes_attempts_bornes CHECK (attempts_left BETWEEN 0 AND 3)
);

-- L'unique lecture : retrouver le code d'une veille à la clôture. L'UNIQUE sur
-- watch_id fournit déjà l'index.
