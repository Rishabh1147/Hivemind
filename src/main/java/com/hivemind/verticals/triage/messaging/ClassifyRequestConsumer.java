package com.hivemind.verticals.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.infra.persistence.TicketRepository;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.messaging.EventBus;
import com.hivemind.platform.messaging.EventConsumer;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.events.ClassifyRequested;
import com.hivemind.verticals.triage.events.TicketClassified;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.TriageResponse;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link ClassifyRequested} events from {@link TriageTopics#CLASSIFY}, runs the existing
 * synchronous {@link ClassifierAgent} against the ticket body, then publishes a
 * {@link TicketClassified} event to {@link TriageTopics#CLASSIFIED} (consumed downstream by
 * {@code TicketClassifiedConsumer}) and materializes the result into {@link TicketRepository} so
 * {@code GET /api/v1/triage/tickets/{id}} has something to read.
 */
@Component
public class ClassifyRequestConsumer extends EventConsumer<ClassifyRequested> {

    private final ClassifierAgent classifierAgent;
    private final EventBus eventBus;
    private final TicketRepository ticketRepository;

    public ClassifyRequestConsumer(
            ClassifierAgent classifierAgent,
            EventBus eventBus,
            TicketRepository ticketRepository,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator) {
        super(objectMapper, ClassifyRequested.class, tracer, propagator);
        this.classifierAgent = classifierAgent;
        this.eventBus = eventBus;
        this.ticketRepository = ticketRepository;
    }

    @KafkaListener(topics = TriageTopics.CLASSIFY)
    public void onClassifyRequested(ConsumerRecord<String, String> record) {
        consume(record.value(), record.headers());
    }

    @Override
    protected void onEvent(ClassifyRequested event) {
        AgentContext context = new AgentContext(event.ticketId());
        context.put(TriageContextKeys.TICKET_BODY, event.body());
        AgentResult<Classification> result = classifierAgent.handle(context);

        TriageResponse response = result.success()
                ? TriageResponse.classified(event.ticketId(), result.payload())
                : TriageResponse.failed(event.ticketId(), result.errorMessage());
        ticketRepository.put(response);

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
