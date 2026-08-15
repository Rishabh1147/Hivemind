package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;

import java.util.List;

/**
 * One case's actual outcome from a harness run, scored against its {@link TriageEvalCase}.
 * Category/routing/citation scoring are all mechanical comparisons that don't need an LLM to check;
 * {@code toneScore} (session 21, once a real, funded key existed to verify it against) is the
 * exception — it comes from {@link TriageEvalToneJudge}'s real Claude call, and is {@code null} for
 * a case with no drafted answer to judge or where the judge call itself failed, per
 * {@link TriageEvalToneJudgment}.
 *
 * <p>{@code costUsd} (session 14) is the sum of {@code ClassifierAgent}'s and {@code ResponderAgent}'s
 * {@code AgentResult.costUsd()} for this case — 0.0 for whichever of the two never ran because an
 * earlier stage failed first, since {@code AgentResult.failure(...)} always carries 0.0.
 * {@code toneJudgeCostUsd} is deliberately a separate field, not folded into {@code costUsd} — see
 * {@link TriageEvalToneJudgment} for why.
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
        Integer toneScore,
        double toneJudgeCostUsd,
        String error) {

    boolean ranSuccessfully() {
        return error == null;
    }
}
