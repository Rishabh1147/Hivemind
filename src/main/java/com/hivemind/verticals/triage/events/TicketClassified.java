package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.PlanDecision;

/**
 * Payload published to {@code hivemind.triage.classified}. Distinct from {@code TriageResponse}
 * (the HTTP-facing DTO) even though the two looked identical while nothing consumed this topic —
 * once {@code RetrieverAgent} needed the original ticket body to search the knowledge base, that
 * coincidence broke, and the correct fix is a dedicated event type, not routing more fields through
 * the HTTP response shape.
 *
 * <p>{@code nextStep} (session 20) is {@code PlannerAgent}'s decision, made once here at classify
 * time and carried forward unchanged onto {@code TicketRetrieved} rather than re-decided at each
 * hop — the same "decide once, thread the result" shape {@code TriageResponse}'s progressive
 * enrichment already uses. {@code null} when classification itself failed, since there's nothing to
 * plan for a ticket with no category.
 */
public record TicketClassified(
        String ticketId, String ticketBody, String status, Category category, Double confidence,
        PlanDecision nextStep, String error) {
}
