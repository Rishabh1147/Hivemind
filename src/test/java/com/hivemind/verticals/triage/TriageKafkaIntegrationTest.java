package com.hivemind.verticals.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.events.TicketRetrieved;
import com.hivemind.verticals.triage.messaging.TriageTopics;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
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
import static org.mockito.Mockito.when;

/**
 * Runs a ticket end-to-end through a real Kafka broker (Testcontainers, not a mock) to verify the
 * event-bus wiring actually works: POST publishes to {@code hivemind.triage.classify},
 * {@link ClassifyRequestConsumer} consumes it and runs the classifier, publishes to
 * {@code hivemind.triage.classified}, {@code TicketClassifiedConsumer} consumes that and runs the
 * real {@code RetrieverAgent} (no mocking — {@code searchKb} is deterministic and cheap), and the
 * final {@link TicketRetrieved} event is read directly off {@code hivemind.triage.retrieved}.
 * {@link ClassifierAgent} is the one thing mocked, since it's the one genuinely external/costly
 * dependency (Claude) — this test is about the Kafka plumbing across both stages, not
 * re-verifying classification logic already covered by {@code ClassifierAgentTest}.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hivemind.llm.api-key=test-key")
class TriageKafkaIntegrationTest {

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

    @MockBean
    private ClassifierAgent classifierAgent;

    @Test
    void ticketFlowsThroughClassifyAndRetrieveStagesOverRealKafka() throws InterruptedException {
        when(classifierAgent.handle(any()))
                .thenReturn(AgentResult.success(new Classification(Category.BILLING, 0.87)));

        ResponseEntity<TriageResponse> postResponse = restTemplate.postForEntity(
                url("/api/v1/triage/tickets"), new Ticket("I was charged twice"), TriageResponse.class);

        assertThat(postResponse.getStatusCode().value()).isEqualTo(202);
        TriageResponse pending = postResponse.getBody();
        assertThat(pending).isNotNull();
        assertThat(pending.status()).isEqualTo("pending");

        TriageResponse classified = pollUntilNoLongerPending(pending.id());

        assertThat(classified.status()).isEqualTo("classified");
        assertThat(classified.category()).isEqualTo(Category.BILLING);
        assertThat(classified.confidence()).isEqualTo(0.87);

        TicketRetrieved retrieved = readNextRetrievedEvent();

        assertThat(retrieved.ticketId()).isEqualTo(pending.id());
        assertThat(retrieved.status()).isEqualTo("retrieved");
        assertThat(retrieved.chunks()).isNotEmpty();
        assertThat(retrieved.chunks().get(0).id()).isEqualTo("billing-duplicate-charge");
    }

    private TicketRetrieved readNextRetrievedEvent() {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "test-retrieved-reader", "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(
                        consumerProps, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(TriageTopics.RETRIEVED));
            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TriageTopics.RETRIEVED, Duration.ofSeconds(10));
            return objectMapper.readValue(record.value(), TicketRetrieved.class);
        } catch (Exception e) {
            throw new AssertionError("Failed to read from " + TriageTopics.RETRIEVED, e);
        }
    }

    private TriageResponse pollUntilNoLongerPending(String ticketId) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            ResponseEntity<TriageResponse> response =
                    restTemplate.getForEntity(url("/api/v1/triage/tickets/" + ticketId), TriageResponse.class);
            TriageResponse body = response.getBody();
            if (body != null && !"pending".equals(body.status())) {
                return body;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Ticket " + ticketId + " never left pending status within 10s");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
