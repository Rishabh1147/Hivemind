package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.agents.ResponderAgent;
import com.hivemind.verticals.triage.agents.RetrieverAgent;
import com.hivemind.verticals.triage.agents.RoutingAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.DraftResponse;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs every {@link TriageEvalCase} under {@code <cases-dir>/triage/} through the classify →
 * retrieve → respond → route chain and scores the result. Deliberately calls the four agents
 * directly and sequentially — the same agents the Kafka consumers call, just not through Kafka —
 * rather than submitting each case as a real ticket and polling. Evals are a batch quality-scoring
 * loop over model decisions, not a re-verification of the event-bus plumbing that
 * {@code TriageKafkaIntegrationTest} already covers; going through Kafka for 50+ cases would add
 * async polling latency and flakiness to a job that should be fast and repeatable.
 */
@Component
public class TriageEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(TriageEvalRunner.class);

    private final ClassifierAgent classifierAgent;
    private final RetrieverAgent retrieverAgent;
    private final ResponderAgent responderAgent;
    private final RoutingAgent routingAgent;
    private final ObjectMapper objectMapper;
    private final Path casesDir;

    public TriageEvalRunner(
            ClassifierAgent classifierAgent,
            RetrieverAgent retrieverAgent,
            ResponderAgent responderAgent,
            RoutingAgent routingAgent,
            ObjectMapper objectMapper,
            @Value("${hivemind.eval.cases-dir:evals}") String casesDir) {
        this.classifierAgent = classifierAgent;
        this.retrieverAgent = retrieverAgent;
        this.responderAgent = responderAgent;
        this.routingAgent = routingAgent;
        this.objectMapper = objectMapper;
        this.casesDir = Path.of(casesDir, "triage");
    }

    public TriageEvalReport run() {
        List<TriageEvalCase> cases = loadCases();
        List<TriageEvalResult> results = new ArrayList<>();
        for (TriageEvalCase evalCase : cases) {
            results.add(runOne(evalCase));
        }
        return aggregate(results);
    }

    private List<TriageEvalCase> loadCases() {
        if (!Files.isDirectory(casesDir)) {
            log.warn("Eval cases directory {} does not exist — running with zero cases", casesDir);
            return List.of();
        }
        try (Stream<Path> files = Files.list(casesDir)) {
            List<TriageEvalCase> cases = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                cases.add(objectMapper.readValue(file.toFile(), TriageEvalCase.class));
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval cases from " + casesDir, e);
        }
    }

    private TriageEvalResult runOne(TriageEvalCase evalCase) {
        long start = System.nanoTime();
        try {
            AgentContext classifyContext = new AgentContext(evalCase.id());
            classifyContext.put(TriageContextKeys.TICKET_BODY, evalCase.ticket());
            AgentResult<Classification> classifyResult = classifierAgent.handle(classifyContext);
            if (!classifyResult.success()) {
                return errorResult(evalCase, start, classifyResult.costUsd(), "classify: " + classifyResult.errorMessage());
            }
            Category actualCategory = classifyResult.payload().category();

            AgentContext retrieveContext = new AgentContext(evalCase.id());
            retrieveContext.put(TriageContextKeys.TICKET_BODY, evalCase.ticket());
            AgentResult<List<KbChunk>> retrieveResult = retrieverAgent.handle(retrieveContext);
            List<KbChunk> chunks = retrieveResult.success() ? retrieveResult.payload() : List.of();

            AgentContext respondContext = new AgentContext(evalCase.id());
            respondContext.put(TriageContextKeys.TICKET_BODY, evalCase.ticket());
            respondContext.put(TriageContextKeys.RETRIEVED_CHUNKS, chunks);
            AgentResult<DraftResponse> respondResult = responderAgent.handle(respondContext);
            double costUsdSoFar = classifyResult.costUsd() + respondResult.costUsd();
            if (!respondResult.success()) {
                return errorResult(evalCase, start, costUsdSoFar, "respond: " + respondResult.errorMessage());
            }
            List<String> citedChunkIds = respondResult.payload().citedChunkIds();

            TriageResponse currentForRouting = new TriageResponse(
                    evalCase.id(), "responded", actualCategory, classifyResult.payload().confidence(),
                    respondResult.payload().answer(), citedChunkIds, null, null);
            AgentContext routeContext = new AgentContext(evalCase.id());
            routeContext.put(TriageContextKeys.CURRENT_TRIAGE_RESPONSE, currentForRouting);
            AgentResult<RoutingDecision> routeResult = routingAgent.handle(routeContext);
            RoutingDecision actualRouting = routeResult.success() ? routeResult.payload() : null;

            return TriageEvalScorer.score(
                    evalCase, actualCategory, actualRouting, citedChunkIds, elapsedMs(start), costUsdSoFar);
        } catch (Exception e) {
            return errorResult(evalCase, start, 0.0, e.getMessage());
        }
    }

    private TriageEvalResult errorResult(TriageEvalCase evalCase, long start, double costUsd, String error) {
        return new TriageEvalResult(
                evalCase.id(), false, false, false, null, null, List.of(), elapsedMs(start), costUsd, error);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private TriageEvalReport aggregate(List<TriageEvalResult> results) {
        int total = results.size();
        long errored = results.stream().filter(r -> !r.ranSuccessfully()).count();
        double categoryAccuracy = rate(results, TriageEvalResult::categoryCorrect);
        double routingAccuracy = rate(results, TriageEvalResult::routingCorrect);
        double citationRecall = rate(results, TriageEvalResult::citationRecallMet);
        double avgCostUsd = results.stream().mapToDouble(TriageEvalResult::costUsd).average().orElse(0.0);

        List<Long> sortedLatencies = results.stream()
                .map(TriageEvalResult::latencyMs)
                .sorted()
                .toList();

        return new TriageEvalReport(
                Instant.now(),
                total,
                (int) errored,
                categoryAccuracy,
                routingAccuracy,
                citationRecall,
                percentile(sortedLatencies, 0.50),
                percentile(sortedLatencies, 0.95),
                avgCostUsd,
                results);
    }

    private double rate(List<TriageEvalResult> results, Predicate<TriageEvalResult> predicate) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().filter(predicate).count() / (double) results.size();
    }

    private long percentile(List<Long> sortedValues, double p) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
