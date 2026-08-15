package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.agents.PlannerAgent;
import com.hivemind.verticals.triage.agents.ResponderAgent;
import com.hivemind.verticals.triage.agents.RetrieverAgent;
import com.hivemind.verticals.triage.agents.RoutingAgent;
import com.hivemind.verticals.triage.agents.TriageContextKeys;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.DraftResponse;
import com.hivemind.verticals.triage.model.PlanDecision;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs every {@link TriageEvalCase} under a cases directory through the classify → (plan) →
 * retrieve → respond → route chain and scores the result. Deliberately calls the agents directly
 * and sequentially — the same agents the Kafka consumers call, just not through Kafka — rather than
 * submitting each case as a real ticket and polling. Evals are a batch quality-scoring loop over
 * model decisions, not a re-verification of the event-bus plumbing that
 * {@code TriageKafkaIntegrationTest} already covers; going through Kafka for 50+ cases would add
 * async polling latency and flakiness to a job that should be fast and repeatable.
 *
 * <p>Since session 20, {@link PlannerAgent} runs after classification here too, and this runner
 * skips {@link ResponderAgent} on {@code PlanDecision.SKIP_RESPONSE} exactly like
 * {@code TicketRetrievedConsumer} does over Kafka — mirroring the real pipeline's branching was the
 * point of adding it here, not just replicating the old always-call-every-agent behavior. Skipping
 * it would mean the eval harness scores (and costs) a pipeline shape that no longer matches what
 * production actually runs for {@code ABUSE} tickets.
 *
 * <p>{@link #run} scores the primary, CI-gated case set under {@code <cases-dir>/triage/}.
 * {@link #runAdversarial} (added for the 20-case set {@code docs/EVALS.md} describes) scores the
 * separate, deliberately-not-gated set under {@code <cases-dir>/triage-adversarial/} through the
 * identical pipeline and scorer — same mechanics, different intent: these cases are expected to
 * surface real, tracked-over-time weaknesses (e.g. citation recall predictably failing on
 * non-English tickets, since retrieval is naive English keyword-overlap, not semantic search) rather
 * than to pass 100% of the time.
 *
 * <p>Since session 21, {@link TriageEvalToneJudge} scores tone (LLM-as-judge) on whatever answer
 * came out of the draft-response path above — a case with no drafted answer (skipped, or errored
 * before reaching this point) simply gets no tone score, not a failing one; see
 * {@link TriageEvalToneJudgment} for why that's "not applicable," not zero.
 *
 * <p>Both methods take a {@code caseIdFilter} — empty means "run every case in the directory," a
 * non-empty set restricts the run to just those ids. This is what backs {@code --case=<id>} on the
 * harness (see {@code TriageEvalHarnessRunner}): every real Claude call this class makes costs real
 * money, and a full 73-case run isn't the right size for "did I wire this correctly" sanity checks
 * during development — the eval harness's own {@code docs/EVALS.md} promised this flag before it
 * existed.
 */
@Component
public class TriageEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(TriageEvalRunner.class);

    private final ClassifierAgent classifierAgent;
    private final PlannerAgent plannerAgent;
    private final RetrieverAgent retrieverAgent;
    private final ResponderAgent responderAgent;
    private final RoutingAgent routingAgent;
    private final TriageEvalToneJudge toneJudge;
    private final ObjectMapper objectMapper;
    private final Path primaryCasesDir;
    private final Path adversarialCasesDir;

    public TriageEvalRunner(
            ClassifierAgent classifierAgent,
            PlannerAgent plannerAgent,
            RetrieverAgent retrieverAgent,
            ResponderAgent responderAgent,
            RoutingAgent routingAgent,
            TriageEvalToneJudge toneJudge,
            ObjectMapper objectMapper,
            @Value("${hivemind.eval.cases-dir:evals}") String casesDir) {
        this.classifierAgent = classifierAgent;
        this.plannerAgent = plannerAgent;
        this.retrieverAgent = retrieverAgent;
        this.responderAgent = responderAgent;
        this.routingAgent = routingAgent;
        this.toneJudge = toneJudge;
        this.objectMapper = objectMapper;
        this.primaryCasesDir = Path.of(casesDir, "triage");
        this.adversarialCasesDir = Path.of(casesDir, "triage-adversarial");
    }

    public TriageEvalReport run(Set<String> caseIdFilter) {
        return runDirectory(primaryCasesDir, caseIdFilter);
    }

    public TriageEvalReport runAdversarial(Set<String> caseIdFilter) {
        return runDirectory(adversarialCasesDir, caseIdFilter);
    }

    private TriageEvalReport runDirectory(Path dir, Set<String> caseIdFilter) {
        List<TriageEvalCase> cases = loadCases(dir);
        if (!caseIdFilter.isEmpty()) {
            cases = cases.stream().filter(evalCase -> caseIdFilter.contains(evalCase.id())).toList();
        }
        List<TriageEvalResult> results = new ArrayList<>();
        for (TriageEvalCase evalCase : cases) {
            results.add(runOne(evalCase));
        }
        return aggregate(results);
    }

    private List<TriageEvalCase> loadCases(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Eval cases directory {} does not exist — running with zero cases", dir);
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<TriageEvalCase> cases = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                cases.add(objectMapper.readValue(file.toFile(), TriageEvalCase.class));
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval cases from " + dir, e);
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

            AgentContext planContext = new AgentContext(evalCase.id());
            planContext.put(TriageContextKeys.CLASSIFICATION, classifyResult.payload());
            PlanDecision nextStep = plannerAgent.handle(planContext).payload();

            String answer;
            List<String> citedChunkIds;
            double costUsdSoFar;
            if (nextStep == PlanDecision.SKIP_RESPONSE) {
                // Mirrors TicketRetrievedConsumer's skip branch: no ResponderAgent call, so no
                // second cost figure to add — citations still come from what was retrieved.
                answer = null;
                citedChunkIds = chunks.stream().map(KbChunk::id).toList();
                costUsdSoFar = classifyResult.costUsd();
            } else {
                AgentContext respondContext = new AgentContext(evalCase.id());
                respondContext.put(TriageContextKeys.TICKET_BODY, evalCase.ticket());
                respondContext.put(TriageContextKeys.RETRIEVED_CHUNKS, chunks);
                AgentResult<DraftResponse> respondResult = responderAgent.handle(respondContext);
                costUsdSoFar = classifyResult.costUsd() + respondResult.costUsd();
                if (!respondResult.success()) {
                    return errorResult(evalCase, start, costUsdSoFar, "respond: " + respondResult.errorMessage());
                }
                answer = respondResult.payload().answer();
                citedChunkIds = respondResult.payload().citedChunkIds();
            }

            TriageResponse currentForRouting = new TriageResponse(
                    evalCase.id(), "responded", actualCategory, classifyResult.payload().confidence(),
                    answer, citedChunkIds, null, null);
            AgentContext routeContext = new AgentContext(evalCase.id());
            routeContext.put(TriageContextKeys.CURRENT_TRIAGE_RESPONSE, currentForRouting);
            AgentResult<RoutingDecision> routeResult = routingAgent.handle(routeContext);
            RoutingDecision actualRouting = routeResult.success() ? routeResult.payload() : null;

            // toneJudge.judge() is itself a no-op (no LLM call) when answer is null, e.g. the
            // PlanDecision.SKIP_RESPONSE path above — nothing to judge the tone of.
            TriageEvalToneJudgment toneJudgment = toneJudge.judge(evalCase.ticket(), answer);

            return TriageEvalScorer.score(
                    evalCase, actualCategory, actualRouting, citedChunkIds, elapsedMs(start), costUsdSoFar,
                    toneJudgment);
        } catch (Exception e) {
            return errorResult(evalCase, start, 0.0, e.getMessage());
        }
    }

    private TriageEvalResult errorResult(TriageEvalCase evalCase, long start, double costUsd, String error) {
        return new TriageEvalResult(
                evalCase.id(), false, false, false, null, null, List.of(), elapsedMs(start), costUsd,
                null, 0.0, error);
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

        List<Integer> toneScores = results.stream()
                .map(TriageEvalResult::toneScore)
                .filter(Objects::nonNull)
                .toList();
        double avgTone = toneScores.stream().mapToInt(Integer::intValue).average().orElse(0.0);

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
                avgTone,
                toneScores.size(),
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
