package com.hivemind.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.memory.AuditLog;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Vertical-agnostic wrapper around Spring Kafka's {@link KafkaTemplate}. Every vertical publishes
 * events through this, never through {@code KafkaTemplate} directly, so OpenTelemetry span
 * injection (roadmap) can be added here once — the same indirection rationale as {@code LlmClient}
 * for Claude calls.
 *
 * <p>As of 2026-07-30, publishing also appends to {@link AuditLog} in the same call — the event log
 * and the audit log are no longer just conceptually the same thing, they're written by the same
 * line of code, so they can't drift out of sync the way two independently-maintained logs could.
 */
@Component
public class EventBus {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLog auditLog;

    public EventBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, AuditLog auditLog) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    public void publish(String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event for topic " + topic, e);
        }
        kafkaTemplate.send(topic, key, payload);
        auditLog.append(verticalFrom(topic), key, event.getClass().getSimpleName(), payload);
    }

    /** Topics follow {@code hivemind.<vertical>.<stage>} — see {@code TopicNaming}. */
    private String verticalFrom(String topic) {
        String[] parts = topic.split("\\.");
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}
