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

/**
 * Entry point for {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval} — runs
 * {@link TriageEvalRunner}, writes the report to {@code eval-results/<timestamp>.json} (gitignored,
 * per {@code docs/EVALS.md}), and gates on {@code hivemind.eval.thresholds.*}, the same thresholds
 * that have sat configured but unread in {@code application.yml} since the project's first session.
 * Exits non-zero on a gating failure — the mechanism a real CI pipeline would call to block a merge,
 * even though there's no CI pipeline wired up to call it yet.
 *
 * <p>Gates on {@code category-accuracy}, {@code citation-recall}, {@code p95-latency-ms}, and, as of
 * session 14, {@code cost-per-ticket-usd} (via {@code CostTracker}, on real token counts from every
 * Claude call — see {@code TriageEvalReport.avgCostUsd()}). {@code tone-min-avg} still needs an
 * LLM-as-judge call no session yet has had a live Anthropic key to verify against, so it stays
 * configured but explicitly not gated on, rather than gated on a number that was never measured.
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

    public TriageEvalHarnessRunner(
            TriageEvalRunner evalRunner,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext,
            @Value("${hivemind.eval.thresholds.category-accuracy}") double categoryAccuracyThreshold,
            @Value("${hivemind.eval.thresholds.citation-recall}") double citationRecallThreshold,
            @Value("${hivemind.eval.thresholds.p95-latency-ms}") long p95LatencyMsThreshold,
            @Value("${hivemind.eval.thresholds.cost-per-ticket-usd}") double costPerTicketUsdThreshold) {
        this.evalRunner = evalRunner;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.categoryAccuracyThreshold = categoryAccuracyThreshold;
        this.citationRecallThreshold = citationRecallThreshold;
        this.p95LatencyMsThreshold = p95LatencyMsThreshold;
        this.costPerTicketUsdThreshold = costPerTicketUsdThreshold;
    }

    @Override
    public void run(String... args) throws Exception {
        TriageEvalReport report = evalRunner.run();
        writeReport(report);

        log.info(
                "Eval run complete: {} cases ({} errored), category accuracy {}, routing accuracy {}, "
                        + "citation recall {}, p50 {}ms, p95 {}ms, avg cost ${}/ticket",
                report.totalCases(), report.erroredCases(), report.categoryAccuracy(), report.routingAccuracy(),
                report.citationRecall(), report.p50LatencyMs(), report.p95LatencyMs(), report.avgCostUsd());

        boolean passed = report.totalCases() > 0
                && report.categoryAccuracy() >= categoryAccuracyThreshold
                && report.citationRecall() >= citationRecallThreshold
                && report.p95LatencyMs() <= p95LatencyMsThreshold
                && report.avgCostUsd() <= costPerTicketUsdThreshold;

        if (!passed) {
            log.error(
                    "Eval run FAILED gating thresholds (category-accuracy>={}, citation-recall>={}, "
                            + "p95-latency-ms<={}, cost-per-ticket-usd<={})",
                    categoryAccuracyThreshold, citationRecallThreshold, p95LatencyMsThreshold, costPerTicketUsdThreshold);
        } else {
            log.info("Eval run PASSED all gating thresholds");
        }

        // SpringApplication.exit(...) only computes the exit code and closes the context — it does
        // not terminate the JVM. Forgetting the surrounding System.exit(...) is a real, easy-to-miss
        // bug: the process exits 0 regardless of gating result unless this call wraps it explicitly.
        System.exit(SpringApplication.exit(applicationContext, () -> passed ? 0 : 1));
    }

    private void writeReport(TriageEvalReport report) throws IOException {
        Path resultsDir = Path.of("eval-results");
        Files.createDirectories(resultsDir);
        Path reportFile = resultsDir.resolve(report.runAt().toString().replace(":", "-") + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.info("Wrote eval report to {}", reportFile);
    }
}
