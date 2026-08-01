package com.hivemind.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Shared shape for every Kafka listener in this codebase: deserialize the raw JSON payload,
 * dropping (logged, not rethrown) anything that doesn't parse, then hand the typed event to
 * {@link #onEvent}, catching and logging anything it throws so one bad event can't crash the
 * listener container thread. Left unbuilt until a second concrete consumer
 * ({@code TicketClassifiedConsumer}) proved the shape was real and not just guessed at from one
 * example; a third ({@code TicketRetrievedConsumer}) is what finally justified extracting it.
 *
 * <p>Also extracts whatever trace context {@link EventBus#publish} injected into the record's
 * headers and opens a span as its child before invoking {@link #onEvent} — this is what makes a
 * ticket's trace continue across the Kafka hop instead of starting a new, disconnected trace per
 * stage. Any {@link EventBus#publish} call made from inside {@link #onEvent} (every consumer in
 * this pipeline makes exactly one, to hand off to the next stage) picks this span up automatically
 * as its parent, since {@link Tracer#nextSpan()} always looks at what's currently in scope.
 *
 * <p>Subclasses still own their own {@code @KafkaListener}-annotated method — Spring needs that on
 * the concrete class — and simply delegate to {@link #consume}.
 */
public abstract class EventConsumer<T> {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ObjectMapper objectMapper;
    private final Class<T> eventType;
    private final Tracer tracer;
    private final Propagator propagator;

    protected EventConsumer(ObjectMapper objectMapper, Class<T> eventType, Tracer tracer, Propagator propagator) {
        this.objectMapper = objectMapper;
        this.eventType = eventType;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    protected final void consume(String rawJson, Headers headers) {
        T event;
        try {
            event = objectMapper.readValue(rawJson, eventType);
        } catch (Exception e) {
            log.error("Dropping unparseable {} message: {}", eventType.getSimpleName(), e.getMessage());
            return;
        }

        Span span = propagator.extract(headers, EventConsumer::getHeader)
                .name("kafka.consume " + eventType.getSimpleName())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            onEvent(event);
        } catch (Exception e) {
            span.error(e);
            log.error("Unhandled error processing {} event: {}", eventType.getSimpleName(), e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    private static String getHeader(Headers headers, String key) {
        var header = headers.lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    protected abstract void onEvent(T event);
}
