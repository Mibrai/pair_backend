-- =================================================================
-- Create Progressions Table - Phase 2 Module 2
-- Système de suivi de progression pour les programmes
-- =================================================================

\echo '=== Creating Progressions Table ==='

CREATE TABLE IF NOT EXISTS progressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id UUID NOT NULL,
    user_id UUID NOT NULL,
    title VARCHAR(150),
    content TEXT,
    metrics FLOAT[],
    metric_labels TEXT[],
    is_public BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_progression_program FOREIGN KEY (program_id)
        REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_progression_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

-- Indexes pour performance
CREATE INDEX IF NOT EXISTS idx_progressions_program
ON progressions(program_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_progressions_user
ON progressions(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_progressions_created
ON progressions(created_at DESC);

-- Index pour recherche de streak (dates consécutives)
CREATE INDEX IF NOT EXISTS idx_progressions_user_date
ON progressions(user_id, DATE(created_at));

\echo ''
\echo '=== Progressions Table Created Successfully! ==='
\echo 'Features:'
\echo '  - Titre et contenu texte'
\echo '  - Métriques numériques (array de floats)'
\echo '  - Labels pour métriques (ex: ["Distance (km)", "Durée (min)"])'
\echo '  - Visibilité public/privé'
\echo '  - Timestamps pour calcul de streak'
\echo '  - Indexes optimisés pour queries fréquentes'
