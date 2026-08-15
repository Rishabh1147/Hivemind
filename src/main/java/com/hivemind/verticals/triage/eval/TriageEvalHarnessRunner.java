package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Entry point for {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval} — runs
 * {@link TriageEvalRunner}, writes the report to {@code eval-results/<timestamp>.json} (gitignored,
 * per {@code docs/EVALS.md}), and gates on {@code hivemind.eval.thresholds.*}, the same thresholds
 * that have sat configured but unread in {@code application.yml} since the project's first session.
 * Exits non-zero on a gating failure — the mechanism a real CI pipeline would call to block a merge,
 * even though there's no CI pipeline wired up to call it yet.
 *
 * <p>Gates on {@code category-accuracy}, {@code citation-recall}, {@code p95-latency-ms}, {@code
 * cost-per-ticket-usd} (session 14, via {@code CostTracker} on real token counts — see {@code
 * TriageEvalReport.avgCostUsd()}), and, as of session 21 (once a real, funded key existed to verify
 * the judge call against), {@code tone-min-avg} — skipped only when {@code toneScoredCases} is zero
 * (see {@code TriageEvalReport}), not gated on a default that would misrepresent "nothing to score"
 * as "scored zero."
 *
 * <p>Also runs {@link TriageEvalRunner#runAdversarial} — the 20-case set {@code docs/EVALS.md}
 * describes — and writes its own report file, but never folds it into the pass/fail decision above:
 * that set is deliberately designed to surface known weaknesses (prompt injection, non-English
 * grounding, and so on) and is tracked over time, not gated, per {@code docs/EVALS.md}.
 *
 * <p>Accepts an optional {@code --case=<id>[,<id>...]} program argument (repeatable, and each value
 * may itself be comma-separated) that restricts both runs above to just those case ids — the flag
 * {@code docs/EVALS.md} named before it existed. Every case run here is a real Claude call; this is
 * what makes "does the wiring still work" a one- or two-case, sub-cent check instead of a run across
 * the full 73-case set every time.
 */
@Component
@Profile("eval")
public class TriageEvalHarnessRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TriageEvalHarnessRunner.class);

    private final TriageEvalRunner evalRunner;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final double categoryAccuracyThreshold;
    private final double citationRecallThreshold;
    private final long p95LatencyMsThreshold;
    private final double costPerTicketUsdThreshold;
    private final double toneMinAvgThreshold;

    public TriageEvalHarnessRunner(
            TriageEvalRunner evalRunner,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext,
            @Value("${hivemind.eval.thresholds.category-accuracy}") double categoryAccuracyThreshold,
            @Value("${hivemind.eval.thresholds.citation-recall}") double citationRecallThreshold,
            @Value("${hivemind.eval.thresholds.p95-latency-ms}") long p95LatencyMsThreshold,
            @Value("${hivemind.eval.thresholds.cost-per-ticket-usd}") double costPerTicketUsdThreshold,
            @Value("${hivemind.eval.thresholds.tone-min-avg}") double toneMinAvgThreshold) {
        this.evalRunner = evalRunner;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.categoryAccuracyThreshold = categoryAccuracyThreshold;
        this.citationRecallThreshold = citationRecallThreshold;
        this.p95LatencyMsThreshold = p95LatencyMsThreshold;
        this.costPerTicketUsdThreshold = costPerTicketUsdThreshold;
        this.toneMinAvgThreshold = toneMinAvgThreshold;
    }

    @Override
    public void run(String... args) throws Exception {
        Set<String> caseIdFilter = parseCaseIdFilter(args);
        if (!caseIdFilter.isEmpty()) {
            log.info("Running eval harness restricted to --case filter: {} (real Claude spend limited to just these ids)",
                    caseIdFilter);
        }

        TriageEvalReport report = evalRunner.run(caseIdFilter);
        writeReport(report, "");

        log.info(
                "Eval run complete: {} cases ({} errored), category accuracy {}, routing accuracy {}, "
                        + "citation recall {}, p50 {}ms, p95 {}ms, avg cost ${}/ticket, avg tone {} ({} scored)",
                report.totalCases(), report.erroredCases(), report.categoryAccuracy(), report.routingAccuracy(),
                report.citationRecall(), report.p50LatencyMs(), report.p95LatencyMs(), report.avgCostUsd(),
                report.avgTone(), report.toneScoredCases());

        // A --case filter that only matches skip-response cases leaves toneScoredCases at 0 — that's
        // "not applicable," not a failing tone score, the same treatment a null expectedRouting gets
        // in TriageEvalScorer. Gating only kicks in once at least one case actually had a tone score.
        boolean toneGatePassed = report.toneScoredCases() == 0 || report.avgTone() >= toneMinAvgThreshold;
        boolean passed = report.totalCases() > 0
                && report.categoryAccuracy() >= categoryAccuracyThreshold
                && report.citationRecall() >= citationRecallThreshold
                && report.p95LatencyMs() <= p95LatencyMsThreshold
                && report.avgCostUsd() <= costPerTicketUsdThreshold
                && toneGatePassed;

        if (!passed) {
            log.error(
                    "Eval run FAILED gating thresholds (category-accuracy>={}, citation-recall>={}, "
                            + "p95-latency-ms<={}, cost-per-ticket-usd<={}, tone-min-avg>={})",
                    categoryAccuracyThreshold, citationRecallThreshold, p95LatencyMsThreshold,
                    costPerTicketUsdThreshold, toneMinAvgThreshold);
        } else {
            log.info("Eval run PASSED all gating thresholds");
        }

        TriageEvalReport adversarialReport = evalRunner.runAdversarial(caseIdFilter);
        writeReport(adversarialReport, "-adversarial");
        log.info(
                "Adversarial eval run complete (not gated, tracked only): {} cases ({} errored), category "
                        + "accuracy {}, citation recall {}",
                adversarialReport.totalCases(), adversarialReport.erroredCases(),
                adversarialReport.categoryAccuracy(), adversarialReport.citationRecall());

        // SpringApplication.exit(...) only computes the exit code and closes the context — it does
        // not terminate the JVM. Forgetting the surrounding System.exit(...) is a real, easy-to-miss
        // bug: the process exits 0 regardless of gating result unless this call wraps it explicitly.
        // Only the primary report's `passed` result decides this — the adversarial run above never
        // affects the exit code, per docs/EVALS.md ("these don't gate CI but are tracked over time").
        System.exit(SpringApplication.exit(applicationContext, () -> passed ? 0 : 1));
    }

    private Set<String> parseCaseIdFilter(String[] args) {
        Set<String> caseIdFilter = new HashSet<>();
        for (String arg : args) {
            if (!arg.startsWith("--case=")) {
                continue;
            }
            for (String id : arg.substring("--case=".length()).split(",")) {
                if (!id.isBlank()) {
                    caseIdFilter.add(id.trim());
                }
            }
        }
        return caseIdFilter;
    }

    private void writeReport(TriageEvalReport report, String suffix) throws IOException {
        Path resultsDir = Path.of("eval-results");
        Files.createDirectories(resultsDir);
        Path reportFile = resultsDir.resolve(report.runAt().toString().replace(":", "-") + suffix + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.info("Wrote eval report to {}", reportFile);
    }
}
