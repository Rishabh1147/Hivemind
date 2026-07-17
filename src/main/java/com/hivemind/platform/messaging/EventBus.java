package com.hivemind.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Vertical-agnostic wrapper around Spring Kafka's {@link KafkaTemplate}. Every vertical publishes
 * events through this, never through {@code KafkaTemplate} directly, so OpenTelemetry span
 * injection and outbox-pattern buffering (both on the roadmap) can be added here once — the same
 * indirection rationale as {@code LlmClient} for Claude calls.
 */
@Component
public class EventBus {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event for topic " + topic, e);
        }
        kafkaTemplate.send(topic, key, payload);
    }
}
