package com.hivemind.verticals.triage.eval;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate scoring across a harness run, written to {@code eval-results/<timestamp>.json}
 * (gitignored — see {@code docs/EVALS.md}). Compared against {@code hivemind.eval.thresholds.*} by
 * {@code TriageEvalHarnessRunner} to decide pass/fail — the same thresholds have sat configured but
 * unused in {@code application.yml} since the very first devlog; this is what finally reads them.
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
        List<TriageEvalResult> results) {
}
