-- Current-state read model, one row per ticket, replacing the in-memory TicketStatusStore.
-- Kept separate from audit_events (V2): this is the "what does this ticket look like right now"
-- table, upserted in place; audit_events is the immutable "what happened, in order" log.
CREATE TABLE tickets (
    id               VARCHAR(64) PRIMARY KEY,
    status           VARCHAR(32) NOT NULL,
    category         VARCHAR(32),
    confidence       DOUBLE PRECISION,
    answer           TEXT,
    cited_chunk_ids  JSONB NOT NULL DEFAULT '[]'::jsonb,
    routing_decision VARCHAR(32),
    error            TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
