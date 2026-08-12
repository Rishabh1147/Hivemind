package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.PlanDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerAgentTest {

    private final PlannerAgent agent = new PlannerAgent();

    @Test
    void skipsResponseDraftingForAbuseRegardlessOfConfidence() {
        AgentResult<PlanDecision> result = plan(new Classification(Category.ABUSE, 0.99));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo(PlanDecision.SKIP_RESPONSE);
    }

    @Test
    void draftsResponseForEveryNonAbuseCategory() {
        for (Category category : Category.values()) {
            if (category == Category.ABUSE) {
                continue;
            }
            AgentResult<PlanDecision> result = plan(new Classification(category, 0.5));

            assertThat(result.success()).isTrue();
            assertThat(result.payload()).as("category %s", category).isEqualTo(PlanDecision.DRAFT_RESPONSE);
        }
    }

    private AgentResult<PlanDecision> plan(Classification classification) {
        AgentContext context = new AgentContext("ticket-1");
        context.put(TriageContextKeys.CLASSIFICATION, classification);
        return agent.handle(context);
    }
}
