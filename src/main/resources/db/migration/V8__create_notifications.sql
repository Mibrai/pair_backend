-- Create notifications table
CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    type            VARCHAR(40) NOT NULL,
    channel         VARCHAR(10) NOT NULL,
    payload         JSONB,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at         TIMESTAMPTZ,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_notif_user ON notifications(user_id);
CREATE INDEX idx_notif_sent_at ON notifications(sent_at);
CREATE INDEX idx_notif_is_read ON notifications(is_read);

-- Create notification_prefs table
CREATE TABLE notification_prefs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    notification_type   VARCHAR(40) NOT NULL,
    email_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    frequency           VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE',
    CONSTRAINT fk_notif_prefs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_notif_pref_user_type UNIQUE (user_id, notification_type)
);
