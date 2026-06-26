-- Drop and recreate activities table properly
DROP TABLE IF EXISTS user_activities CASCADE;
DROP TABLE IF EXISTS activities CASCADE;

-- Create activities table
CREATE TABLE activities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID,
    category_id UUID NOT NULL,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(150) NOT NULL UNIQUE,
    description VARCHAR(500),
    embedding   vector(1536),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_activities_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT
);

-- Add self-referencing constraint after table creation
ALTER TABLE activities
ADD CONSTRAINT fk_activities_parent FOREIGN KEY (parent_id) REFERENCES activities(id) ON DELETE CASCADE;

CREATE INDEX idx_activities_slug ON activities(slug);
CREATE INDEX idx_activities_category ON activities(category_id);

-- Create user_activities table
CREATE TABLE user_activities (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    activity_id         UUID NOT NULL,
    visible_on_map      BOOLEAN NOT NULL DEFAULT TRUE,
    custom_description  VARCHAR(500),
    level               VARCHAR(20) DEFAULT 'ANY',
    format              VARCHAR(10) DEFAULT 'ANY',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ua_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ua_activity FOREIGN KEY (activity_id) REFERENCES activities(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_activity UNIQUE (user_id, activity_id)
);

CREATE INDEX idx_ua_user ON user_activities(user_id);
CREATE INDEX idx_ua_activity ON user_activities(activity_id);
CREATE INDEX idx_ua_visible ON user_activities(visible_on_map);
