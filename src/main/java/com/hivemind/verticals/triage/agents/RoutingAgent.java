package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.agent.AgentRole;
import com.hivemind.platform.agent.BaseAgent;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.springframework.beans.factory.annotation.Value;

/**
 * The Route step from {@code ARCHITECTURE.md}: auto-resolve, queue for a human, or escalate.
 * Deliberately <em>not</em> an LLM call, unlike the other three agents — the decision is a
 * deterministic function of category and confidence, and routing a ticket through Claude for a
 * decision that's actually a pure function of two known values would add latency, cost, and
 * non-determinism to something that doesn't need any of them.
 */
@AgentRole(vertical = "triage", role = "router")
public class RoutingAgent extends BaseAgent<RoutingDecision> {

    private final double autoResolveConfidenceThreshold;

    public RoutingAgent(
            @Value("${hivemind.triage.routing.auto-resolve-confidence-threshold:0.8}") double autoResolveConfidenceThreshold) {
        this.autoResolveConfidenceThreshold = autoResolveConfidenceThreshold;
    }

    @Override
    public AgentResult<RoutingDecision> handle(AgentContext context) {
        TriageResponse current = (TriageResponse) context.get(TriageContextKeys.CURRENT_TRIAGE_RESPONSE);

        if ("response_failed".equals(current.status())) {
            return AgentResult.success(RoutingDecision.ESCALATE);
        }
        if (current.category() == Category.ABUSE) {
            return AgentResult.success(RoutingDecision.ESCALATE);
        }
        if (current.confidence() != null && current.confidence() >= autoResolveConfidenceThreshold) {
            return AgentResult.success(RoutingDecision.AUTO_RESOLVE);
        }
        return AgentResult.success(RoutingDecision.QUEUE_FOR_HUMAN);
    }
}
