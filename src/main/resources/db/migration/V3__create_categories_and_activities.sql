-- Create categories table
CREATE TABLE IF NOT EXISTS categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(80) NOT NULL UNIQUE,
    icon        VARCHAR(80),
    color_ramp  VARCHAR(30)
);

-- Create activities table
CREATE TABLE IF NOT EXISTS activities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID,
    category_id UUID NOT NULL,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(150) NOT NULL UNIQUE,
    description VARCHAR(500),
    embedding   vector(1536),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_activities_parent FOREIGN KEY (parent_id) REFERENCES activities(id) ON DELETE CASCADE,
    CONSTRAINT fk_activities_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_activities_slug ON activities(slug);
CREATE INDEX IF NOT EXISTS idx_activities_category ON activities(category_id);

-- Create HNSW index for semantic search
CREATE INDEX IF NOT EXISTS idx_activities_embedding ON activities USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
