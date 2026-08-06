package com.hivemind.verticals.triage.eval;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate scoring across a harness run, written to {@code eval-results/<timestamp>.json}
 * (gitignored — see {@code docs/EVALS.md}). Compared against {@code hivemind.eval.thresholds.*} by
 * {@code TriageEvalHarnessRunner} to decide pass/fail — the same thresholds have sat configured but
 * unused in {@code application.yml} since the very first devlog; this is what finally reads them.
 *
 * <p>{@code avgCostUsd} (session 14) is the mean of every case's {@code TriageEvalResult.costUsd()}
 * — including cases that errored partway through, since whatever LLM calls did complete before the
 * failure still cost real money.
 */
public record TriageEvalReport(
        Instant runAt,
        int totalCases,
        int erroredCases,
        double categoryAccuracy,
        double routingAccuracy,
        double citationRecall,
        long p50LatencyMs,
        long p95LatencyMs,
        double avgCostUsd,
        List<TriageEvalResult> results) {
}
