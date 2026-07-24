-- meetDo: boucle de confirmation "j'y étais" après un créneau.

CREATE TABLE attendances (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id),
    was_present  BOOLEAN NOT NULL,
    attended_at  TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attendance UNIQUE (schedule_id, user_id)
);

CREATE INDEX idx_attendance_user_date ON attendances(user_id, attended_at DESC);
CREATE INDEX idx_attendance_schedule  ON attendances(schedule_id);

-- Compteurs dénormalisés sur users (métriques de valeur : régularité et
-- partenaires différents, jamais un score comparatif)
ALTER TABLE users
    ADD COLUMN distinct_partners_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN attendance_count        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN current_streak_weeks    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attendance_at      TIMESTAMPTZ;
