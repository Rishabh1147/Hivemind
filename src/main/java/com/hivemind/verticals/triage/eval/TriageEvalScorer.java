package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;

import java.util.List;

/**
 * Pure comparison logic, deliberately separated from {@link TriageEvalRunner} so it's testable
 * without running any agent — given a case and what actually happened, did it pass. No LLM calls,
 * no I/O, just equality/containment checks.
 */
public final class TriageEvalScorer {

    private TriageEvalScorer() {
    }

    public static TriageEvalResult score(
            TriageEvalCase evalCase, Category actualCategory, RoutingDecision actualRouting, List<String> actualCitedChunkIds, long latencyMs) {
        boolean categoryCorrect = actualCategory == evalCase.expectedCategory();
        // Routing depends on model confidence, which isn't knowable when a case is authored — a
        // null expectedRouting means "not applicable to this case," not "must be null."
        boolean routingCorrect = evalCase.expectedRouting() == null || actualRouting == evalCase.expectedRouting();
        boolean citationRecallMet = evalCase.mustCite().isEmpty()
                || evalCase.mustCite().stream().anyMatch(actualCitedChunkIds::contains);

        return new TriageEvalResult(
                evalCase.id(), categoryCorrect, routingCorrect, citationRecallMet,
                actualCategory, actualRouting, actualCitedChunkIds, latencyMs, null);
    }
}
