package com.hivemind.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared shape for every Kafka listener in this codebase: deserialize the raw JSON payload,
 * dropping (logged, not rethrown) anything that doesn't parse, then hand the typed event to
 * {@link #onEvent}, catching and logging anything it throws so one bad event can't crash the
 * listener container thread. Left unbuilt until a second concrete consumer
 * ({@code TicketClassifiedConsumer}) proved the shape was real and not just guessed at from one
 * example; a third ({@code TicketRetrievedConsumer}) is what finally justified extracting it.
 *
 * <p>Subclasses still own their own {@code @KafkaListener}-annotated method — Spring needs that on
 * the concrete class — and simply delegate to {@link #consume}.
 */
public abstract class EventConsumer<T> {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ObjectMapper objectMapper;
    private final Class<T> eventType;

    protected EventConsumer(ObjectMapper objectMapper, Class<T> eventType) {
        this.objectMapper = objectMapper;
        this.eventType = eventType;
    }

    protected final void consume(String rawJson) {
        T event;
        try {
            event = objectMapper.readValue(rawJson, eventType);
        } catch (Exception e) {
            log.error("Dropping unparseable {} message: {}", eventType.getSimpleName(), e.getMessage());
            return;
        }
        try {
            onEvent(event);
        } catch (Exception e) {
            log.error("Unhandled error processing {} event: {}", eventType.getSimpleName(), e.getMessage(), e);
        }
    }

    protected abstract void onEvent(T event);
}
