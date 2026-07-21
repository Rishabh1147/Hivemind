package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.model.Category;

/**
 * Payload published to {@code hivemind.triage.classified}. Distinct from {@code TriageResponse}
 * (the HTTP-facing DTO) even though the two looked identical while nothing consumed this topic —
 * once {@code RetrieverAgent} needed the original ticket body to search the knowledge base, that
 * coincidence broke, and the correct fix is a dedicated event type, not routing more fields through
 * the HTTP response shape.
 */
public record TicketClassified(
        String ticketId, String ticketBody, String status, Category category, Double confidence, String error) {
}
