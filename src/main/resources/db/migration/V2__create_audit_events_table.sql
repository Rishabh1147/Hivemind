-- Immutable, append-only: one row per event ever published to Kafka via EventBus. This is the
-- literal implementation of ARCHITECTURE.md's "the event log is the audit log" — EventBus.publish()
-- writes here in the same call that sends to Kafka, so the two never drift out of sync.
CREATE TABLE audit_events (
    id         BIGSERIAL PRIMARY KEY,
    vertical   VARCHAR(64) NOT NULL,
    entity_id  VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload    JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_entity_id ON audit_events (entity_id);
