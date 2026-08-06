package com.hivemind.verticals.triage.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.llm.CostTracker;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.platform.llm.LlmResponse;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.DraftResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponderAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CostTracker costTracker = new CostTracker(3.00, 15.00);

    @Test
    void draftsAnswerWithCitationsFromWellFormedResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn(new LlmResponse(
                "{\"answer\": \"We found a duplicate charge and issued a refund.\", "
                        + "\"citedChunkIds\": [\"billing-duplicate-charge\"]}",
                new TokenUsage(200, 40)));
        ResponderAgent agent = new ResponderAgent(llmClient, costTracker, objectMapper);

        AgentContext context = new AgentContext("ticket-1");
        context.put(TriageContextKeys.TICKET_BODY, "I was charged twice");
        context.put(TriageContextKeys.RETRIEVED_CHUNKS,
                List.of(new KbChunk("billing-duplicate-charge", "Duplicate charges", "...")));

        AgentResult<DraftResponse> result = agent.handle(context);

        assertThat(result.success()).isTrue();
        assertThat(result.payload().answer()).contains("refund");
        assertThat(result.payload().citedChunkIds()).containsExactly("billing-duplicate-charge");
        // 200 input tokens @ $3/M + 40 output tokens @ $15/M = 0.0006 + 0.0006
        assertThat(result.costUsd()).isEqualTo(0.0012);
    }

    @Test
    void draftsAnswerEvenWithNoRetrievedChunks() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn(new LlmResponse(
                "{\"answer\": \"We don't have specific policy on this yet, escalating to a human.\", "
                        + "\"citedChunkIds\": []}",
                new TokenUsage(150, 30)));
        ResponderAgent agent = new ResponderAgent(llmClient, costTracker, objectMapper);

        AgentContext context = new AgentContext("ticket-2");
        context.put(TriageContextKeys.TICKET_BODY, "Something unusual");
        context.put(TriageContextKeys.RETRIEVED_CHUNKS, List.of());

        AgentResult<DraftResponse> result = agent.handle(context);

        assertThat(result.success()).isTrue();
        assertThat(result.payload().citedChunkIds()).isEmpty();
    }

    @Test
    void failsGracefullyWhenLlmCallThrows() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenThrow(new RuntimeException("invalid x-api-key"));
        ResponderAgent agent = new ResponderAgent(llmClient, costTracker, objectMapper);

        AgentContext context = new AgentContext("ticket-3");
        context.put(TriageContextKeys.TICKET_BODY, "some ticket body");
        context.put(TriageContextKeys.RETRIEVED_CHUNKS, List.of());

        AgentResult<DraftResponse> result = agent.handle(context);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Responder LLM call failed");
        assertThat(result.costUsd()).isEqualTo(0.0);
    }

    @Test
    void failsGracefullyOnMalformedResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn(new LlmResponse("not json", new TokenUsage(5, 0)));
        ResponderAgent agent = new ResponderAgent(llmClient, costTracker, objectMapper);

        AgentContext context = new AgentContext("ticket-4");
        context.put(TriageContextKeys.TICKET_BODY, "some ticket body");
        context.put(TriageContextKeys.RETRIEVED_CHUNKS, List.of());

        AgentResult<DraftResponse> result = agent.handle(context);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Failed to parse responder response");
    }
}
