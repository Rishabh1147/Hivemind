package com.hivemind.verticals.triage.model;

import java.util.List;

/**
 * Progressively enriched as a ticket moves through the pipeline: {@code pending} at submission,
 * {@code classified}/{@code classification_failed} once {@code ClassifyRequestConsumer} runs, then
 * {@code responded}/{@code response_failed} once {@code TicketRetrievedConsumer} runs
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

    public TriageResponse withRouting(RoutingDecision decision) {
        return new TriageResponse(id, "routed", category, confidence, answer, citedChunkIds, decision, null);
    }

    public TriageResponse withRoutingFailure(String error) {
        return new TriageResponse(id, "routing_failed", category, confidence, answer, citedChunkIds, null, error);
    }
}
