package com.hivemind.verticals.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.infra.persistence.TicketRepository;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.messaging.EventBus;
import com.hivemind.platform.messaging.EventConsumer;
import com.hivemind.verticals.triage.agents.ResponderAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.events.TicketRetrieved;
import com.hivemind.verticals.triage.events.TicketResponded;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.DraftResponse;
import com.hivemind.verticals.triage.model.PlanDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The third Kafka consumer in the pipeline: consumes {@link TicketRetrieved} events from
 * {@link TriageTopics#RETRIEVED}, runs {@link ResponderAgent}, and publishes a
 * {@link TicketResponded} event to {@link TriageTopics#RESPONDED}. Tickets where retrieval itself
 * failed are skipped — there's nothing reliable to draft a reply from.
 *
 * <p>Since session 20, {@code ResponderAgent} itself is also conditional: when
 * {@code event.nextStep()} is {@code PlanDecision.SKIP_RESPONSE} ({@code PlannerAgent}'s call, made
 * once at classify time), this consumer publishes a {@code response_skipped} outcome directly —
 * still with citations from what was retrieved — without spending a Claude call drafting a reply
 * nothing downstream reads.
 *
 * <p>Unlike the two consumers before it, this one <em>does</em> write into
 * {@link TicketRepository} — it's the stage that finally has something worth surfacing to
 * {@code GET /api/v1/triage/tickets/{id}}, closing the gap both earlier consumers left open on
 * purpose.
 */
@Component
public class TicketRetrievedConsumer extends EventConsumer<TicketRetrieved> {

    private static final Logger log = LoggerFactory.getLogger(TicketRetrievedConsumer.class);
    private static final String RETRIEVED_STATUS = "retrieved";

    private final ResponderAgent responderAgent;
    private final EventBus eventBus;
    private final TicketRepository ticketRepository;

    public TicketRetrievedConsumer(
            ResponderAgent responderAgent,
            EventBus eventBus,
            TicketRepository ticketRepository,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator) {
        super(objectMapper, TicketRetrieved.class, tracer, propagator);
        this.responderAgent = responderAgent;
        this.eventBus = eventBus;
        this.ticketRepository = ticketRepository;
    }

    @KafkaListener(topics = TriageTopics.RETRIEVED)
    public void onTicketRetrieved(ConsumerRecord<String, String> record) {
        consume(record.value(), record.headers());
    }

    @Override
    protected void onEvent(TicketRetrieved event) {
        if (!RETRIEVED_STATUS.equals(event.status())) {
            log.info("Skipping response drafting for ticket {} (retrieval status: {})", event.ticketId(), event.status());
            return;
        }

        TriageResponse current = ticketRepository.get(event.ticketId()).orElse(TriageResponse.pending(event.ticketId()));

        if (event.nextStep() == PlanDecision.SKIP_RESPONSE) {
            skipResponseDrafting(event, current);
            return;
        }

        AgentContext context = new AgentContext(event.ticketId());
        context.put(TriageContextKeys.TICKET_BODY, event.ticketBody());
        context.put(TriageContextKeys.RETRIEVED_CHUNKS, event.chunks());
        AgentResult<DraftResponse> result = responderAgent.handle(context);

        TriageResponse updated = result.success()
                ? current.withResponse(result.payload())
                : current.withResponseFailure(result.errorMessage());
        ticketRepository.put(updated);

        TicketResponded responded = result.success()
                ? new TicketResponded(event.ticketId(), "responded", result.payload().answer(), result.payload().citedChunkIds(), null)
                : new TicketResponded(event.ticketId(), "response_failed", null, List.of(), result.errorMessage());
        eventBus.publish(TriageTopics.RESPONDED, event.ticketId(), responded);
    }

    /**
     * {@code PlannerAgent} decided this ticket doesn't need a drafted reply — skip the
     * {@code ResponderAgent} Claude call entirely rather than draft something nothing downstream
     * will read ({@code RoutingAgent} escalates on category alone for the one case this applies to
     * today). The retrieved chunks still become {@code citedChunkIds}, so the grounding trail stays
     * on record even with no drafted answer.
     */
    private void skipResponseDrafting(TicketRetrieved event, TriageResponse current) {
        List<String> citedChunkIds = event.chunks().stream().map(KbChunk::id).toList();
        ticketRepository.put(current.withResponseSkipped(citedChunkIds));

        TicketResponded responded = new TicketResponded(event.ticketId(), "response_skipped", null, citedChunkIds, null);
        eventBus.publish(TriageTopics.RESPONDED, event.ticketId(), responded);
    }
}
