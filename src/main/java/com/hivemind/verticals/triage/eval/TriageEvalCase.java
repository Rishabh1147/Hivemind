package com.hivemind.verticals.triage.eval;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;

import java.util.List;

/**
 * One hand-written/synthesized ticket + its gold-standard expected outcome, loaded from
 * {@code evals/triage/<id>.json} (data, not source — see {@code docs/EVALS.md} for why eval cases
 * live outside {@code src/}). Field names here are camelCase rather than {@code EVALS.md}'s original
 * {@code snake_case} sketch (e.g. {@code must_cite} → {@code mustCite}) — the design doc predates
 * implementation; matching Java naming conventions in the actual JSON was a deliberate small
 * deviation, not an oversight.
 */
public record TriageEvalCase(String id, String ticket, Category expectedCategory, RoutingDecision expectedRouting, List<String> mustCite) {
}
