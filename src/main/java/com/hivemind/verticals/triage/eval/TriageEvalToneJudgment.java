package com.hivemind.verticals.triage.eval;

/**
 * One case's tone score from {@link TriageEvalToneJudge}. {@code score} is {@code null} for a case
 * with no drafted answer to judge (an errored case, or a {@code PlanDecision.SKIP_RESPONSE} case —
 * there's no reply to rate the tone of) or a case where the judge call itself failed; neither counts
 * as a bad score, they're excluded from the average entirely, the same "not applicable, not a
 * failure" treatment {@code TriageEvalScorer} already gives a null {@code expectedRouting}.
 *
 * <p>{@code costUsd} is kept separate from {@link TriageEvalResult#costUsd()} rather than folded
 * into it — that field feeds the {@code cost-per-ticket-usd} gate, which exists to catch real
 * production cost regressions, and the judge call is evaluation-only overhead that never runs in
 * production. Folding it in would make that gate quietly measure something it wasn't built to
 * measure.
 */
public record TriageEvalToneJudgment(Integer score, double costUsd, String error) {

    static TriageEvalToneJudgment notApplicable() {
        return new TriageEvalToneJudgment(null, 0.0, null);
    }
}
