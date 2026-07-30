package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TriageEvalScorerTest {

    @Test
    void scoresAPerfectMatchAsFullyCorrect() {
        TriageEvalCase evalCase = new TriageEvalCase(
                "case-1", "some ticket", Category.BILLING, RoutingDecision.ESCALATE, List.of("chunk-a"));

        TriageEvalResult result = TriageEvalScorer.score(
                evalCase, Category.BILLING, RoutingDecision.ESCALATE, List.of("chunk-a", "chunk-b"), 42);

        assertThat(result.categoryCorrect()).isTrue();
        assertThat(result.routingCorrect()).isTrue();
        assertThat(result.citationRecallMet()).isTrue();
        assertThat(result.latencyMs()).isEqualTo(42);
    }

    @Test
    void flagsWrongCategoryAndWrongRouting() {
        TriageEvalCase evalCase = new TriageEvalCase(
                "case-2", "some ticket", Category.BILLING, RoutingDecision.AUTO_RESOLVE, List.of());

        TriageEvalResult result = TriageEvalScorer.score(
                evalCase, Category.BUG, RoutingDecision.QUEUE_FOR_HUMAN, List.of(), 10);

        assertThat(result.categoryCorrect()).isFalse();
        assertThat(result.routingCorrect()).isFalse();
    }

    @Test
    void treatsANullExpectedRoutingAsNotApplicableRatherThanFailed() {
        TriageEvalCase evalCase = new TriageEvalCase("case-3", "some ticket", Category.BUG, null, List.of());

        TriageEvalResult result = TriageEvalScorer.score(evalCase, Category.BUG, RoutingDecision.AUTO_RESOLVE, List.of(), 5);

        assertThat(result.routingCorrect()).isTrue();
    }

    @Test
    void citationRecallPassesWhenAnyRequiredChunkWasCited() {
        TriageEvalCase evalCase = new TriageEvalCase(
                "case-4", "some ticket", Category.BILLING, null, List.of("required-a", "required-b"));

        TriageEvalResult result = TriageEvalScorer.score(
                evalCase, Category.BILLING, null, List.of("required-b", "unrelated"), 5);

        assertThat(result.citationRecallMet()).isTrue();
    }

    @Test
    void citationRecallFailsWhenNoRequiredChunkWasCited() {
        TriageEvalCase evalCase = new TriageEvalCase("case-5", "some ticket", Category.BILLING, null, List.of("required-a"));

        TriageEvalResult result = TriageEvalScorer.score(evalCase, Category.BILLING, null, List.of("unrelated"), 5);

        assertThat(result.citationRecallMet()).isFalse();
    }

    @Test
    void citationRecallIsVacuouslyMetWhenCaseRequiresNoCitations() {
        TriageEvalCase evalCase = new TriageEvalCase("case-6", "some ticket", Category.OTHER, null, List.of());

        TriageEvalResult result = TriageEvalScorer.score(evalCase, Category.OTHER, null, List.of(), 5);

        assertThat(result.citationRecallMet()).isTrue();
    }
}
