-- =================================================================
-- Enable pgvector extension for semantic search
-- Phase 2: Recherche intelligente
-- =================================================================

\echo '=== Activating pgvector extension ==='

-- Install vector extension
CREATE EXTENSION IF NOT EXISTS vector;

\echo ''
\echo '=== Adding embedding columns to tables ==='

-- Add embedding column to activities table
ALTER TABLE activities
ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- Add embedding column to programs table
ALTER TABLE programs
ADD COLUMN IF NOT EXISTS embedding vector(1536);

\echo ''
\echo '=== Creating vector indexes ==='

-- Create ivfflat index for activities
-- Note: ivfflat is efficient for datasets > 1000 rows
-- For smaller datasets, use hnsw or no index
CREATE INDEX IF NOT EXISTS idx_activities_embedding
ON activities USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 10);

-- Create ivfflat index for programs
CREATE INDEX IF NOT EXISTS idx_programs_embedding
ON programs USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 10);

\echo ''
\echo '=== Verifying pgvector installation ==='

-- Show extension info
\dx vector

-- Check columns
SELECT
    table_name,
    column_name,
    data_type
FROM information_schema.columns
WHERE column_name = 'embedding'
    AND table_schema = 'public';

\echo ''
\echo '=== pgvector enabled successfully! ==='
\echo 'Extension: vector 0.8.3'
\echo 'Embedding dimension: 1536 (OpenAI text-embedding-3-small)'
\echo 'Index type: ivfflat with cosine distance'
