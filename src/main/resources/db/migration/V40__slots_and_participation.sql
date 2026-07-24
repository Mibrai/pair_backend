-- meetDo: le créneau (Schedule) devient un objet ouvert et rejoignable.

ALTER TABLE schedules
    ADD COLUMN is_open_to_partners BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN participant_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN welcome_note        VARCHAR(300);

CREATE INDEX idx_schedules_status ON schedules(status);
CREATE INDEX idx_schedules_open   ON schedules(is_open_to_partners, starts_at);

CREATE TABLE slot_participations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'INTERESTED',
    join_message VARCHAR(300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_slot_user UNIQUE (schedule_id, user_id)
);

CREATE INDEX idx_slotpart_schedule ON slot_participations(schedule_id);
CREATE INDEX idx_slotpart_user     ON slot_participations(user_id);
CREATE INDEX idx_slotpart_status   ON slot_participations(status);
