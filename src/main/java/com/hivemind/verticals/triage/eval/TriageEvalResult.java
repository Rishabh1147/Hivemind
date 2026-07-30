package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;

import java.util.List;

/**
 * One case's actual outcome from a harness run, scored against its {@link TriageEvalCase}. Tone
 * scoring (LLM-as-judge, per {@code docs/EVALS.md}) is deliberately not included yet — it needs its
 * own real Claude call to verify, the same as every other LLM-calling piece of this codebase, and
 * this session didn't have a live Anthropic key to verify it against. Category/routing/citation
 * scoring are all mechanical comparisons that don't need an LLM to check.
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
        String error) {

    boolean ranSuccessfully() {
        return error == null;
    }
}
