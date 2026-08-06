package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;

import java.util.List;

/**
 * One case's actual outcome from a harness run, scored against its {@link TriageEvalCase}. Tone
 * scoring (LLM-as-judge, per {@code docs/EVALS.md}) is deliberately not included yet — it needs its
 * own real Claude call to verify, the same as every other LLM-calling piece of this codebase, and
 * no session yet has had a live Anthropic key to verify it against. Category/routing/citation
 * scoring are all mechanical comparisons that don't need an LLM to check.
 *
 * <p>{@code costUsd} (session 14) is the sum of {@code ClassifierAgent}'s and {@code ResponderAgent}'s
 * {@code AgentResult.costUsd()} for this case — 0.0 for whichever of the two never ran because an
 * earlier stage failed first, since {@code AgentResult.failure(...)} always carries 0.0.
 */
public record TriageEvalResult(
        String caseId,
        boolean categoryCorrect,
        boolean routingCorrect,
        boolean citationRecallMet,
        Category actualCategory,
        RoutingDecision actualRouting,
        List<String> actualCitedChunkIds,
        long latencyMs,
        double costUsd,
        String error) {

    boolean ranSuccessfully() {
        return error == null;
    }
}
