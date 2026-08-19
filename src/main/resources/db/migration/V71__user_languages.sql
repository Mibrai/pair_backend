-- Langues parlées (lot D1 de meetdo-v2)
--
-- Numérotée V71 et non V70 : le lot C4 a eu besoin d'une migration que la
-- spécification ne prévoyait pas, et toute la phase D se décale d'un rang.
--
-- Ce que ces colonnes servent : savoir avec qui on pourra se parler. Rien
-- d'autre — ni classement, ni score de compatibilité.

CREATE TABLE IF NOT EXISTS user_languages (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Étiquette courte : fr, en, de, es, it… Volontairement pas une énumération
    -- PostgreSQL : la liste des langues du monde n'a pas à passer par une
    -- migration pour s'allonger, et le serveur valide déjà la forme.
    language    VARCHAR(5) NOT NULL,

    -- NATIVE | FLUENT | CONVERSATIONAL | BASIC. Déclaratif, jamais vérifié —
    -- comme les tags d'accessibilité du lot suivant, et le contrat d'API le dit.
    proficiency VARCHAR(20) NOT NULL,

    PRIMARY KEY (user_id, language)
);

-- Le filtre demande « qui parle telle langue » : c'est par la langue qu'on
-- entre, jamais par la personne.
CREATE INDEX IF NOT EXISTS idx_user_languages_lang ON user_languages(language);

-- Langue principale d'un créneau.
--
-- Nullable, et c'est le cas normal : la plupart des créneaux n'en déclareront
-- jamais. Un créneau sans langue déclarée n'est donc <b>jamais exclu</b> par le
-- filtre — même principe que la ville, qui n'est jamais devinée. Exclure faute
-- d'information reviendrait à punir ceux qui n'ont rien rempli.
ALTER TABLE schedules
    ADD COLUMN IF NOT EXISTS primary_language VARCHAR(5);
