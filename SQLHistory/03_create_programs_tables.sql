-- =================================================================
-- Create Programs, Schedules, and Program Media tables
-- Phase 1 Step 5: Programmes & Créneaux
-- =================================================================

\echo '=== Creating Programs Table ==='
CREATE TABLE IF NOT EXISTS programs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_activity_id    UUID NOT NULL,
    title               VARCHAR(150) NOT NULL,
    description         VARCHAR(3000),
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    is_public           BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_program_user_activity FOREIGN KEY (user_activity_id)
        REFERENCES user_activities(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_programs_user_activity ON programs(user_activity_id);
CREATE INDEX IF NOT EXISTS idx_programs_status ON programs(status);
CREATE INDEX IF NOT EXISTS idx_programs_public ON programs(is_public);

\echo '=== Creating Schedules Table ==='
CREATE TABLE IF NOT EXISTS schedules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id          UUID NOT NULL,
    place_name          VARCHAR(200) NOT NULL,
    place_type          VARCHAR(10) NOT NULL,
    location            geometry(Point,4326) NOT NULL,
    address_public      VARCHAR(300),
    show_exact_address  BOOLEAN NOT NULL DEFAULT FALSE,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ,
    recurrence_rule     VARCHAR(255),
    max_participants    INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_schedule_program FOREIGN KEY (program_id)
        REFERENCES programs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_schedules_program ON schedules(program_id);
CREATE INDEX IF NOT EXISTS idx_schedules_starts_at ON schedules(starts_at);
CREATE INDEX IF NOT EXISTS idx_schedules_location ON schedules USING GIST(location);

\echo '=== Creating Program Media Table ==='
CREATE TABLE IF NOT EXISTS program_media (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      UUID NOT NULL,
    url             VARCHAR(500) NOT NULL,
    media_type      VARCHAR(10) NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_media_program FOREIGN KEY (program_id)
        REFERENCES programs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_media_program ON program_media(program_id);
CREATE INDEX IF NOT EXISTS idx_media_sort ON program_media(program_id, sort_order);

\echo ''
\echo '=== Programs Tables Created Successfully! ==='
\echo 'Tables:'
\echo '  - programs'
\echo '  - schedules (with PostGIS support)'
\echo '  - program_media'
\echo ''
