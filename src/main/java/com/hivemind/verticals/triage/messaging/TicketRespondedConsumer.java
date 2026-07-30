package com.hivemind.verticals.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.infra.persistence.TicketRepository;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.messaging.EventBus;
import com.hivemind.platform.messaging.EventConsumer;
import com.hivemind.verticals.triage.agents.RoutingAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.events.TicketResponded;
import com.hivemind.verticals.triage.events.TicketRouted;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The fourth and final Kafka consumer in the pipeline: consumes {@link TicketResponded} events
 * from {@link TriageTopics#RESPONDED}, runs {@link RoutingAgent}, and publishes a
 * {@link TicketRouted} event to {@link TriageTopics#ROUTED}.
 *
 * <p>Unlike every earlier consumer, this one does <em>not</em> skip when the upstream stage
 * failed — a ticket that failed to get a drafted answer is exactly the ticket that most needs to
 * reach a human, so a {@code response_failed} ticket still gets routed (to {@code ESCALATE}, via
 * {@link RoutingAgent}'s own handling of that status) rather than being silently left unrouted the
 * way a {@code classification_failed} ticket has nothing left to retrieve or respond to.
 */
@Component
public class TicketRespondedConsumer extends EventConsumer<TicketResponded> {

    private final RoutingAgent routingAgent;
    private final EventBus eventBus;
    private final TicketRepository ticketRepository;

    public TicketRespondedConsumer(
            RoutingAgent routingAgent, EventBus eventBus, TicketRepository ticketRepository, ObjectMapper objectMapper) {
        super(objectMapper, TicketResponded.class);
        this.routingAgent = routingAgent;
        this.eventBus = eventBus;
        this.ticketRepository = ticketRepository;
    }

    @KafkaListener(topics = TriageTopics.RESPONDED)
    public void onTicketResponded(String rawJson) {
        consume(rawJson);
    }

    @Override
    protected void onEvent(TicketResponded event) {
        TriageResponse current = ticketRepository.get(event.ticketId()).orElse(TriageResponse.pending(event.ticketId()));

        AgentContext context = new AgentContext(event.ticketId());
        context.put(TriageContextKeys.CURRENT_TRIAGE_RESPONSE, current);
        AgentResult<RoutingDecision> result = routingAgent.handle(context);

        TriageResponse updated = result.success()
                ? current.withRouting(result.payload())
                : current.withRoutingFailure(result.errorMessage());
        ticketRepository.put(updated);

        TicketRouted routed = result.success()
                ? new TicketRouted(event.ticketId(), "routed", result.payload(), null)
                : new TicketRouted(event.ticketId(), "routing_failed", null, result.errorMessage());
        eventBus.publish(TriageTopics.ROUTED, event.ticketId(), routed);
    }
}
