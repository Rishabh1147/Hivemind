package com.hivemind.verticals.triage.model;

import java.util.List;

/**
 * Progressively enriched as a ticket moves through the pipeline: {@code pending} at submission,
 * {@code classified}/{@code classification_failed} once {@code ClassifyRequestConsumer} runs, then
 * {@code responded}/{@code response_failed}/{@code response_skipped} once {@code
 * TicketRetrievedConsumer} runs (or, per {@code PlannerAgent}'s decision, deliberately doesn't run)
 * {@code ResponderAgent}, then {@code routed} once {@code TicketRespondedConsumer} runs
 * {@code RoutingAgent}. Each {@code with*} method preserves every field it isn't explicitly
 * changing — a ticket at the routed stage still carries its classify and respond results, not just
 * the latest one.
 */
public record TriageResponse(
        String id,
        String status,
        Category category,
        Double confidence,
        String answer,
        List<String> citedChunkIds,
        RoutingDecision routingDecision,
        String error) {

    public static TriageResponse pending(String id) {
        return new TriageResponse(id, "pending", null, null, null, List.of(), null, null);
    }

    public static TriageResponse classified(String id, Classification classification) {
        return new TriageResponse(
                id, "classified", classification.category(), classification.confidence(), null, List.of(), null, null);
    }

    public static TriageResponse failed(String id, String error) {
        return new TriageResponse(id, "classification_failed", null, null, null, List.of(), null, error);
    }

    public TriageResponse withResponse(DraftResponse draft) {
        return new TriageResponse(
                id, "responded", category, confidence, draft.answer(), draft.citedChunkIds(), routingDecision, null);
    }

    public TriageResponse withResponseFailure(String error) {
        return new TriageResponse(id, "response_failed", category, confidence, null, List.of(), routingDecision, error);
    }

    /**
     * {@code PlannerAgent} decided this ticket doesn't get a drafted reply (today: {@code ABUSE}
     * tickets, which {@code RoutingAgent} escalates unconditionally regardless of any drafted
     * answer). {@code citedChunkIds} still comes from retrieval — a deliberate skip, not a failure,
     * so {@code answer} is null but the grounding trail stays on record.
     */
    public TriageResponse withResponseSkipped(List<String> citedChunkIds) {
        return new TriageResponse(id, "response_skipped", category, confidence, null, citedChunkIds, routingDecision, null);
    }

    public TriageResponse withRouting(RoutingDecision decision) {
        return new TriageResponse(id, "routed", category, confidence, answer, citedChunkIds, decision, null);
    }

    public TriageResponse withRoutingFailure(String error) {
        return new TriageResponse(id, "routing_failed", category, confidence, answer, citedChunkIds, null, error);
    }
}
