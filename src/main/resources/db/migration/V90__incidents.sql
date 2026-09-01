-- Le registre des incidents de sécurité (priorité 5 en écriture, priorité 7 en
-- lecture et en API).
--
-- Séparé de reports à dessein : mêler « perdu en chemin » à « comportement
-- inapproprié » polluerait la modération et mettrait la victime dans la colonne
-- des signalés. Seule la cible PERSON basculera, plus tard, vers le flux de
-- signalement existant.

CREATE TABLE IF NOT EXISTS incidents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target      VARCHAR(12) NOT NULL,
    watch_id    UUID REFERENCES watches(id) ON DELETE SET NULL,
    schedule_id UUID REFERENCES schedules(id) ON DELETE SET NULL,
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT incidents_target_vocabulaire
        CHECK (target IN ('PERSON', 'PLACE', 'ORGANISATION', 'TRANSIT', 'SELF'))
);

-- La lecture de la priorité 7 : mes incidents, du plus récent au plus ancien.
CREATE INDEX IF NOT EXISTS idx_incidents_user ON incidents(user_id, created_at DESC);
