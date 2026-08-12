package com.hivemind.verticals.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.AbstractPostgresIntegrationTest;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.agents.ResponderAgent;
import com.hivemind.verticals.triage.events.TicketRetrieved;
import com.hivemind.verticals.triage.events.TicketResponded;
import com.hivemind.verticals.triage.events.TicketRouted;
import com.hivemind.verticals.triage.messaging.TriageTopics;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.DraftResponse;
import com.hivemind.verticals.triage.model.PlanDecision;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.Ticket;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs a ticket end-to-end through a real Kafka broker (Testcontainers, not a mock) across all
 * four pipeline stages: POST publishes to {@code hivemind.triage.classify},
 * {@link ClassifierAgent} classifies (mocked — the genuinely external/costly Claude call),
 * {@code TicketClassifiedConsumer} runs the real {@code RetrieverAgent} (no mocking — {@code
 * searchKb} is deterministic and cheap), {@code TicketRetrievedConsumer} runs
 * {@link ResponderAgent} (mocked — also a Claude call), and {@code TicketRespondedConsumer} runs
 * the real {@code RoutingAgent} (no mocking — it's a deterministic policy, not an LLM call) to a
 * final {@link TicketRouted} event. Both LLM-calling agents are test-doubled since re-verifying
 * their own logic is {@code ClassifierAgentTest}/{@code ResponderAgentTest}'s job; every
 * infrastructure/deterministic-logic stage is left fully real. Also asserts directly against
 * Postgres (via {@link AbstractPostgresIntegrationTest}) that the final row in {@code tickets}
 * matches what the API returns, and that {@code audit_events} really did accumulate one row per
 * published event — proving {@code EventBus.publish()}'s dual Kafka+audit write happened for real,
 * not just that the HTTP response looked right.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hivemind.llm.api-key=test-key")
class TriageKafkaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ClassifierAgent classifierAgent;

    @MockBean
    private ResponderAgent responderAgent;

    @Test
    void ticketFlowsThroughAllFourStagesOverRealKafka() throws InterruptedException {
        when(classifierAgent.handle(any()))
                .thenReturn(AgentResult.success(new Classification(Category.BILLING, 0.87)));
        when(responderAgent.handle(any())).thenReturn(AgentResult.success(
                new DraftResponse("We found a duplicate charge and issued a refund.", List.of("billing-duplicate-charge"))));

        ResponseEntity<TriageResponse> postResponse = restTemplate.postForEntity(
                url("/api/v1/triage/tickets"), new Ticket("I was charged twice"), TriageResponse.class);

        assertThat(postResponse.getStatusCode().value()).isEqualTo(202);
        TriageResponse pending = postResponse.getBody();
        assertThat(pending).isNotNull();
        assertThat(pending.status()).isEqualTo("pending");

        // Read the intermediate event straight off Kafka rather than polling the HTTP status for
        // "classified" — the pipeline can advance past that status before a 250ms poll catches it,
        // since every stage here runs near-instantly (mocked LLM calls, in-memory KB). The final
        // "responded" status is polled below instead, since it's the terminal state.
        TicketRetrieved retrieved = readNextEvent(TriageTopics.RETRIEVED, TicketRetrieved.class);

        assertThat(retrieved.ticketId()).isEqualTo(pending.id());
        assertThat(retrieved.status()).isEqualTo("retrieved");
        assertThat(retrieved.chunks()).isNotEmpty();
        assertThat(retrieved.chunks().get(0).id()).isEqualTo("billing-duplicate-charge");

        // Same reasoning as above: read the intermediate "responded" event off Kafka directly
        // rather than polling HTTP for it, since it's no longer the terminal status either now
        // that routing follows it.
        TicketResponded respondedEvent = readNextEvent(TriageTopics.RESPONDED, TicketResponded.class);

        assertThat(respondedEvent.ticketId()).isEqualTo(pending.id());
        assertThat(respondedEvent.status()).isEqualTo("responded");
        assertThat(respondedEvent.answer()).contains("refund");

        TriageResponse routed = pollUntilStatus(pending.id(), "routed");

        assertThat(routed.category()).isEqualTo(Category.BILLING);
        assertThat(routed.confidence()).isEqualTo(0.87);
        assertThat(routed.answer()).contains("refund");
        assertThat(routed.citedChunkIds()).containsExactly("billing-duplicate-charge");
        assertThat(routed.routingDecision()).isEqualTo(RoutingDecision.AUTO_RESOLVE);

        TicketRouted routedEvent = readNextEvent(TriageTopics.ROUTED, TicketRouted.class);

        assertThat(routedEvent.ticketId()).isEqualTo(pending.id());
        assertThat(routedEvent.status()).isEqualTo("routed");
        assertThat(routedEvent.routingDecision()).isEqualTo(RoutingDecision.AUTO_RESOLVE);

        // Real Postgres assertions, not just the HTTP view of it: confirm TicketRepository actually
        // persisted the row (the "tickets" upsert), and that EventBus's dual write means one
        // audit_events row exists per event this ticket generated (classify, classified, retrieved,
        // responded, routed — 5 total, since the controller's own ClassifyRequested publish counts).
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tickets WHERE id = ?", String.class, pending.id());
        assertThat(status).isEqualTo("routed");

        Integer auditEventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ?", Integer.class, pending.id());
        assertThat(auditEventCount).isEqualTo(5);
    }

    /**
     * Same real-Kafka shape as the test above, but for the branch {@code PlannerAgent} added
     * (session 20): an {@code ABUSE} classification should make {@code TicketRetrievedConsumer}
     * skip {@code ResponderAgent} entirely rather than draft a reply nothing downstream reads.
     * {@code verify(responderAgent, never())} is the assertion that actually matters here — the
     * final routed state alone (ESCALATE) can't distinguish "skipped as designed" from "drafted a
     * response and escalated anyway," since {@code RoutingAgent} escalates every {@code ABUSE}
     * ticket unconditionally either way.
     */
    @Test
    void skipsResponseDraftingForAbuseTicketsButStillCitesAndEscalates() throws InterruptedException {
        when(classifierAgent.handle(any()))
                .thenReturn(AgentResult.success(new Classification(Category.ABUSE, 0.99)));

        ResponseEntity<TriageResponse> postResponse = restTemplate.postForEntity(
                url("/api/v1/triage/tickets"),
                new Ticket("Multiple accounts are sending unsolicited bulk messages and attempting credential stuffing"),
                TriageResponse.class);

        assertThat(postResponse.getStatusCode().value()).isEqualTo(202);
        TriageResponse pending = postResponse.getBody();
        assertThat(pending).isNotNull();

        TicketRetrieved retrieved = readNextEvent(TriageTopics.RETRIEVED, TicketRetrieved.class);
        assertThat(retrieved.nextStep()).isEqualTo(PlanDecision.SKIP_RESPONSE);
        assertThat(retrieved.chunks()).extracting("id").contains("abuse-policy");

        TicketResponded respondedEvent = readNextEvent(TriageTopics.RESPONDED, TicketResponded.class);
        assertThat(respondedEvent.status()).isEqualTo("response_skipped");
        assertThat(respondedEvent.answer()).isNull();
        assertThat(respondedEvent.citedChunkIds()).contains("abuse-policy");

        TriageResponse routed = pollUntilStatus(pending.id(), "routed");
        assertThat(routed.category()).isEqualTo(Category.ABUSE);
        assertThat(routed.answer()).isNull();
        assertThat(routed.citedChunkIds()).contains("abuse-policy");
        assertThat(routed.routingDecision()).isEqualTo(RoutingDecision.ESCALATE);

        // Both tests in this class read hivemind.triage.routed through the same deterministic
        // "test-<topic>-reader" consumer group (see readNextEvent) — draining it here, exactly like
        // the test above does, is what keeps that shared group's offset from leaking an uncommitted
        // record into whichever test reads this topic next.
        TicketRouted routedEvent = readNextEvent(TriageTopics.ROUTED, TicketRouted.class);
        assertThat(routedEvent.status()).isEqualTo("routed");
        assertThat(routedEvent.routingDecision()).isEqualTo(RoutingDecision.ESCALATE);

        // The assertion the rest of this test exists to set up: ResponderAgent — a real Claude call
        // in production — was never invoked for this ticket.
        verify(responderAgent, never()).handle(any());
    }

    private <T> T readNextEvent(String topic, Class<T> eventType) {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "test-" + topic + "-reader", "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(
                        consumerProps, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));
            return objectMapper.readValue(record.value(), eventType);
        } catch (Exception e) {
            throw new AssertionError("Failed to read from " + topic, e);
        }
    }

    private TriageResponse pollUntilStatus(String ticketId, String expectedStatus) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            ResponseEntity<TriageResponse> response =
                    restTemplate.getForEntity(url("/api/v1/triage/tickets/" + ticketId), TriageResponse.class);
            TriageResponse body = response.getBody();
            if (body != null && expectedStatus.equals(body.status())) {
                return body;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Ticket " + ticketId + " never reached status '" + expectedStatus + "' within 10s");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
