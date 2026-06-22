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
    CONSTRAINT fk_user_activities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_activities_activity FOREIGN KEY (activity_id) REFERENCES activities(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_activity UNIQUE (user_id, activity_id)
);

CREATE INDEX idx_ua_user ON user_activities(user_id);
CREATE INDEX idx_ua_activity ON user_activities(activity_id);
CREATE INDEX idx_ua_visible ON user_activities(visible_on_map);
