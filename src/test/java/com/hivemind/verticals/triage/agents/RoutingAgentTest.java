package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingAgentTest {

    private final RoutingAgent agent = new RoutingAgent(0.8);

    @Test
    void autoResolvesHighConfidenceNonAbuseTicket() {
        AgentResult<RoutingDecision> result = route(responded("ticket-1", Category.BILLING, 0.92));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo(RoutingDecision.AUTO_RESOLVE);
    }

    @Test
    void queuesForHumanWhenConfidenceIsBelowThreshold() {
        AgentResult<RoutingDecision> result = route(responded("ticket-2", Category.BUG, 0.5));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo(RoutingDecision.QUEUE_FOR_HUMAN);
    }

    @Test
    void escalatesAbuseRegardlessOfConfidence() {
        AgentResult<RoutingDecision> result = route(responded("ticket-3", Category.ABUSE, 0.99));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo(RoutingDecision.ESCALATE);
    }

    @Test
    void escalatesWhenResponseDraftingFailed() {
        TriageResponse failed = TriageResponse.pending("ticket-4")
                .withResponseFailure("Responder LLM call failed: invalid x-api-key");

        AgentResult<RoutingDecision> result = route(failed);

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo(RoutingDecision.ESCALATE);
    }

    private AgentResult<RoutingDecision> route(TriageResponse current) {
        AgentContext context = new AgentContext(current.id());
        context.put(TriageContextKeys.CURRENT_TRIAGE_RESPONSE, current);
        return agent.handle(context);
    }

    private TriageResponse responded(String id, Category category, double confidence) {
        return new TriageResponse(id, "responded", category, confidence, "an answer", java.util.List.of(), null, null);
    }
}
