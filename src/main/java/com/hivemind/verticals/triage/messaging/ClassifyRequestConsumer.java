package com.hivemind.verticals.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.messaging.EventBus;
import com.hivemind.verticals.triage.TicketStatusStore;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.events.ClassifyRequested;
import com.hivemind.verticals.triage.events.TicketClassified;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link ClassifyRequested} events from {@link TriageTopics#CLASSIFY}, runs the existing
 * synchronous {@link ClassifierAgent} against the ticket body, then publishes a
 * {@link TicketClassified} event to {@link TriageTopics#CLASSIFIED} (consumed downstream by
 * {@code TicketClassifiedConsumer}) and materializes the result into {@link TicketStatusStore} so
 * {@code GET /api/v1/triage/tickets/{id}} has something to read.
 *
 * <p>No generic {@code EventConsumer} base class yet — same discipline as not building
 * {@code ToolRegistry} before a second tool exists: wait for a second consumer before extracting
 * the shared deserialize/error-contain shape. That second consumer now exists
 * ({@code TicketClassifiedConsumer}); the extraction is deferred to a session where it's the
 * primary focus rather than a side effect of adding it.
 */
@Component
public class ClassifyRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClassifyRequestConsumer.class);

    private final ClassifierAgent classifierAgent;
    private final EventBus eventBus;
    private final TicketStatusStore ticketStatusStore;
    private final ObjectMapper objectMapper;

    public ClassifyRequestConsumer(
            ClassifierAgent classifierAgent,
            EventBus eventBus,
            TicketStatusStore ticketStatusStore,
            ObjectMapper objectMapper) {
        this.classifierAgent = classifierAgent;
        this.eventBus = eventBus;
        this.ticketStatusStore = ticketStatusStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TriageTopics.CLASSIFY)
    public void onClassifyRequested(String rawJson) {
        ClassifyRequested event;
        try {
            event = objectMapper.readValue(rawJson, ClassifyRequested.class);
        } catch (Exception e) {
            log.error("Dropping unparseable {} message: {}", TriageTopics.CLASSIFY, e.getMessage());
            return;
        }

        AgentContext context = new AgentContext(event.ticketId());
        context.put(TriageContextKeys.TICKET_BODY, event.body());
        AgentResult<Classification> result = classifierAgent.handle(context);

        TriageResponse response = result.success()
                ? TriageResponse.classified(event.ticketId(), result.payload())
                : TriageResponse.failed(event.ticketId(), result.errorMessage());
        ticketStatusStore.put(response);

        TicketClassified classified = new TicketClassified(
                event.ticketId(),
                event.body(),
                response.status(),
                response.category(),
                response.confidence(),
                response.error());
        eventBus.publish(TriageTopics.CLASSIFIED, event.ticketId(), classified);
    }
}
