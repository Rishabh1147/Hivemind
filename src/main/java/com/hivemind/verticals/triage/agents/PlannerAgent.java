package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.agent.AgentRole;
import com.hivemind.platform.agent.BaseAgent;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.PlanDecision;

/**
 * Decides whether the pipeline should draft a customer-facing response at all — the first real
 * instance of the pipeline branching on a decision instead of running four fixed Kafka hops every
 * time. Deliberately <em>not</em> an LLM call, the same reasoning {@link RoutingAgent} already
 * applies to the final routing decision: this is a deterministic function of the classification
 * result, and spending latency, cost, and non-determinism on an LLM call to make a decision that's
 * actually a pure function of one known value would be the wrong trade.
 *
 * <p>Today's rule: an {@code ABUSE} ticket never gets a drafted reply — {@link RoutingAgent} escalates
 * every {@code ABUSE} ticket unconditionally regardless of what (if anything) got drafted, so drafting
 * one is real cost (a Claude call) spent on an answer nothing downstream ever uses. Retrieval still
 * runs either way — {@code SearchKbTool}'s keyword-overlap results stay on record as the ticket's
 * grounding/audit trail even when no reply gets written from them, which is what lets a case like
 * {@code abuse-001} (gold-labeled to cite {@code abuse-policy}) still pass a citation check with no
 * response drafted. That does trade citation <em>precision</em> for citation <em>recall</em> once
 * {@link com.hivemind.verticals.triage.messaging.TicketRetrievedConsumer} skips drafting — every
 * retrieved chunk becomes a "citation" rather than only the ones Claude would have judged actually
 * relevant — but this codebase already scores recall only, not precision (see {@code docs/EVALS.md}),
 * so that trade doesn't change what's actually measured today.
 *
 * <p>This is a first real decision, not the full planner {@code ARCHITECTURE.md} originally sketched
 * — it doesn't reorder stages, retry, or decide retrieval itself. Extend it the session a second real
 * branching rule earns its place, not speculatively.
 */
@AgentRole(vertical = "triage", role = "planner")
public class PlannerAgent extends BaseAgent<PlanDecision> {

    @Override
    public AgentResult<PlanDecision> handle(AgentContext context) {
        Classification classification = (Classification) context.get(TriageContextKeys.CLASSIFICATION);
        PlanDecision decision = classification.category() == Category.ABUSE
                ? PlanDecision.SKIP_RESPONSE
                : PlanDecision.DRAFT_RESPONSE;
        return AgentResult.success(decision);
    }
}
