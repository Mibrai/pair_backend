-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   VARCHAR(255) NOT NULL UNIQUE,
    password_hash           VARCHAR(255) NOT NULL,
    phone                   VARCHAR(20),
    display_name            VARCHAR(80) NOT NULL,
    bio                     VARCHAR(1000),
    avatar_url              VARCHAR(500),
    location                GEOMETRY(Point, 4326),
    blur_radius_m           INTEGER NOT NULL DEFAULT 500,
    location_public         BOOLEAN NOT NULL DEFAULT FALSE,
    online_status_visible   BOOLEAN NOT NULL DEFAULT FALSE,
    receive_messages        BOOLEAN NOT NULL DEFAULT TRUE,
    verification_status     VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED',
    verified_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at          TIMESTAMPTZ,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_last_active ON users(last_active_at);
CREATE INDEX IF NOT EXISTS idx_users_location ON users USING GIST(location);
