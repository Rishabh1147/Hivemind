package com.hivemind.verticals.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.messaging.EventBus;
import com.hivemind.verticals.triage.agents.RetrieverAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.events.TicketClassified;
import com.hivemind.verticals.triage.events.TicketRetrieved;
import com.hivemind.verticals.triage.kb.KbChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The second Kafka consumer in the pipeline: consumes {@link TicketClassified} events from
 * {@link TriageTopics#CLASSIFIED}, runs {@link RetrieverAgent} against the ticket body, and
 * publishes a {@link TicketRetrieved} event to {@link TriageTopics#RETRIEVED}. Tickets that failed
 * classification are skipped — there's nothing meaningful to search for on a ticket whose category
 * is unknown.
 *
 * <p>Unlike {@link ClassifyRequestConsumer}, this stage doesn't write into {@code TicketStatusStore}:
 * {@code GET /api/v1/triage/tickets/{id}} still reports {@code classified}, since there's no
 * Responder yet to consume retrieved chunks into a user-visible result. Retrieval is verified today
 * by reading {@link TriageTopics#RETRIEVED} directly, the same way the event log is meant to double
 * as the audit trail for every stage.
 */
@Component
public class TicketClassifiedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketClassifiedConsumer.class);
    private static final String CLASSIFIED_STATUS = "classified";

    private final RetrieverAgent retrieverAgent;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public TicketClassifiedConsumer(RetrieverAgent retrieverAgent, EventBus eventBus, ObjectMapper objectMapper) {
        this.retrieverAgent = retrieverAgent;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TriageTopics.CLASSIFIED)
    public void onTicketClassified(String rawJson) {
        TicketClassified event;
        try {
            event = objectMapper.readValue(rawJson, TicketClassified.class);
        } catch (Exception e) {
            log.error("Dropping unparseable {} message: {}", TriageTopics.CLASSIFIED, e.getMessage());
            return;
        }

        if (!CLASSIFIED_STATUS.equals(event.status())) {
            log.info("Skipping retrieval for ticket {} (classification status: {})", event.ticketId(), event.status());
            return;
        }

        AgentContext context = new AgentContext(event.ticketId());
        context.put(TriageContextKeys.TICKET_BODY, event.ticketBody());
        AgentResult<List<KbChunk>> result = retrieverAgent.handle(context);

        TicketRetrieved retrieved = result.success()
                ? new TicketRetrieved(event.ticketId(), "retrieved", result.payload(), null)
                : new TicketRetrieved(event.ticketId(), "retrieval_failed", List.of(), result.errorMessage());

        eventBus.publish(TriageTopics.RETRIEVED, event.ticketId(), retrieved);
    }
}
