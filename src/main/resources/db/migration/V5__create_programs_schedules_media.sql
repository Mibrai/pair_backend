-- Create programs table
CREATE TABLE programs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_activity_id    UUID NOT NULL,
    title               VARCHAR(150) NOT NULL,
    description         TEXT,
    embedding           vector(1536),
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    is_public           BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT fk_programs_user_activity FOREIGN KEY (user_activity_id) REFERENCES user_activities(id) ON DELETE CASCADE
);

CREATE INDEX idx_programs_user_activity ON programs(user_activity_id);
CREATE INDEX idx_programs_status ON programs(status);
CREATE INDEX idx_programs_archived ON programs(archived_at);
CREATE INDEX idx_programs_embedding ON programs USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- Create schedules table
CREATE TABLE schedules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id          UUID NOT NULL,
    place_name          VARCHAR(200) NOT NULL,
    place_type          VARCHAR(10) NOT NULL,
    location            GEOMETRY(Point, 4326) NOT NULL,
    address_public      VARCHAR(300),
    show_exact_address  BOOLEAN NOT NULL DEFAULT FALSE,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ,
    recurrence_rule     VARCHAR(200),
    max_participants    INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_schedules_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE
);

CREATE INDEX idx_schedules_program ON schedules(program_id);
CREATE INDEX idx_schedules_starts_at ON schedules(starts_at);
CREATE INDEX idx_schedules_location ON schedules USING GIST(location);

-- Create program_media table
CREATE TABLE program_media (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id  UUID NOT NULL,
    url         VARCHAR(500) NOT NULL,
    media_type  VARCHAR(10) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_program_media_program FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE CASCADE
);

CREATE INDEX idx_media_program ON program_media(program_id);
