-- meetDo: alerte "préviens-moi quand quelqu'un pratique cette activité ici"
-- (anti-carte-vide).

CREATE TABLE activity_alerts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_id       UUID NOT NULL REFERENCES activities(id),
    location          GEOMETRY(Point, 4326) NOT NULL,
    radius_meters     INTEGER NOT NULL DEFAULT 10000,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    last_triggered_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alert_user_activity UNIQUE (user_id, activity_id)
);

CREATE INDEX idx_alert_activity ON activity_alerts(activity_id);
CREATE INDEX idx_alert_user     ON activity_alerts(user_id);
CREATE INDEX idx_alert_location ON activity_alerts USING GIST(location);
