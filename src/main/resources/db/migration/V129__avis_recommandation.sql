-- Demande mobile avis du 15/09/2026 (P-MU-02 étape 2, décision D4) : « Tu le
-- recommanderais ? Oui » posé avec l'avis privé. Un booléen, jamais une note ;
-- les avis existants n'ont rien recommandé.

ALTER TABLE reviews ADD COLUMN IF NOT EXISTS recommend BOOLEAN NOT NULL DEFAULT FALSE;
