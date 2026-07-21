package com.hivemind.verticals.triage.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassifierAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesWellFormedClassifierResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn("{\"category\": \"BILLING\", \"confidence\": 0.92}");
        ClassifierAgent agent = new ClassifierAgent(llmClient, objectMapper);

        AgentContext context = new AgentContext("ticket-1");
        context.put(TriageContextKeys.TICKET_BODY, "I was charged twice for my subscription");

        AgentResult<Classification> result = agent.handle(context);

        assertThat(result.success()).isTrue();
        assertThat(result.payload().category()).isEqualTo(Category.BILLING);
        assertThat(result.payload().confidence()).isEqualTo(0.92);
    }

    @Test
    void failsGracefullyWhenLlmCallThrows() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenThrow(new RuntimeException("invalid x-api-key"));
        ClassifierAgent agent = new ClassifierAgent(llmClient, objectMapper);

        AgentContext context = new AgentContext("ticket-3");
        context.put(TriageContextKeys.TICKET_BODY, "some ticket body");

        AgentResult<Classification> result = agent.handle(context);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Classifier LLM call failed");
    }

    @Test
    void failsGracefullyOnMalformedResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn("not json");
        ClassifierAgent agent = new ClassifierAgent(llmClient, objectMapper);

        AgentContext context = new AgentContext("ticket-2");
        context.put(TriageContextKeys.TICKET_BODY, "some ticket body");

        AgentResult<Classification> result = agent.handle(context);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Failed to parse classifier response");
    }
}
