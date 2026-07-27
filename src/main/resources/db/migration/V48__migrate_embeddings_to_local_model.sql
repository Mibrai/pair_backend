-- Bascule des colonnes d'embedding de 1536 dimensions (OpenAI text-embedding-3-small)
-- vers 384 dimensions (sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2,
-- local, trilingue FR/EN/DE). USING NULL : les anciens vecteurs OpenAI sont
-- dimensionnellement incompatibles avec le nouveau modèle et ne peuvent pas être
-- convertis — réindexation via POST /api/indexation/backfill-embeddings après déploiement.

DROP INDEX IF EXISTS idx_activities_embedding;
DROP INDEX IF EXISTS idx_programs_embedding;
DROP INDEX IF EXISTS idx_search_logs_embedding;

ALTER TABLE activities  ALTER COLUMN embedding TYPE vector(384) USING NULL;
ALTER TABLE programs    ALTER COLUMN embedding TYPE vector(384) USING NULL;
ALTER TABLE search_logs ALTER COLUMN query_embedding TYPE vector(384) USING NULL;

CREATE INDEX idx_activities_embedding
    ON activities USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_programs_embedding
    ON programs USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_search_logs_embedding
    ON search_logs USING hnsw (query_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
