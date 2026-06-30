-- Phase 4 Module 1: Device tokens for push notifications
-- Note: notifications and notification_prefs already created in V8

CREATE TABLE IF NOT EXISTS device_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token        VARCHAR(500) NOT NULL UNIQUE,
    platform     VARCHAR(20) NOT NULL,
    device_name  VARCHAR(100),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user  ON device_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_device_tokens_token ON device_tokens(token);
