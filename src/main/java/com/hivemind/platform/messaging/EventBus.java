package com.hivemind.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.memory.AuditLog;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Vertical-agnostic wrapper around Spring Kafka's {@link KafkaTemplate}. Every vertical publishes
 * events through this, never through {@code KafkaTemplate} directly, so both the trace-context
 * injection below and the audit write are guaranteed to happen for every event, not just the ones a
 * caller remembers to instrument — the same indirection rationale as {@code LlmClient} for Claude
 * calls.
 *
 * <p>Each publish opens a child span of whatever span is current on the calling thread (the HTTP
 * request span for the controller's initial publish, or the consumer span set up by
 * {@link EventConsumer} for every downstream stage) and injects it into the Kafka record's headers.
 * {@link EventConsumer#consume} extracts it back out on the other side, so one trace id follows a
 * ticket through the entire async pipeline instead of stopping at the first Kafka hop.
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
    private final Tracer tracer;
    private final Propagator propagator;

    public EventBus(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            AuditLog auditLog,
            Tracer tracer,
            Propagator propagator) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void publish(String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event for topic " + topic, e);
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        Span span = tracer.nextSpan().name("kafka.publish " + topic).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("messaging.system", "kafka");
            span.tag("messaging.destination", topic);
            propagator.inject(span.context(), record.headers(), EventBus::setHeader);
            kafkaTemplate.send(record);
            auditLog.append(verticalFrom(topic), key, event.getClass().getSimpleName(), payload);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static void setHeader(Headers headers, String key, String value) {
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Topics follow {@code hivemind.<vertical>.<stage>} — see {@code TopicNaming}. */
    private String verticalFrom(String topic) {
        String[] parts = topic.split("\\.");
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}
