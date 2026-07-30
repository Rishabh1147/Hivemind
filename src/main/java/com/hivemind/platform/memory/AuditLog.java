package com.hivemind.platform.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Immutable, append-only record of every event {@code EventBus} publishes — the literal
 * implementation of {@code ARCHITECTURE.md}'s "the event log is the audit log," not just a
 * metaphor about Kafka topics. Vertical-agnostic on purpose (deals only in strings and JSON, the
 * same way {@code EventBus} itself doesn't know what a "ticket" is): {@code platform/} may depend
 * on generic Spring/JDBC infrastructure, just never on a specific vertical's types.
 */
@Component
public class AuditLog {

    private final JdbcTemplate jdbcTemplate;

    public AuditLog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(String vertical, String entityId, String eventType, String payloadJson) {
        jdbcTemplate.update(
                "INSERT INTO audit_events (vertical, entity_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                vertical, entityId, eventType, payloadJson);
    }
}
