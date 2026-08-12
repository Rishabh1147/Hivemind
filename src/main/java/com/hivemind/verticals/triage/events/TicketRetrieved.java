package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.PlanDecision;

import java.util.List;

/**
 * Payload published to {@code hivemind.triage.retrieved}. Carries {@code ticketBody} from the
 * start this time — {@code TicketClassified} had to be split out of {@code TriageResponse}
 * specifically because it didn't carry the body a downstream consumer needed; same reasoning
 * applied up front here instead of rediscovering the gap when {@code ResponderAgent} needed it.
 *
 * <p>{@code nextStep} (session 20) is carried forward unchanged from {@code TicketClassified} —
 * {@code TicketRetrievedConsumer} reads it to decide whether to run {@code ResponderAgent} at all,
 * without needing to re-derive {@code PlannerAgent}'s decision or re-read the classification.
 */
public record TicketRetrieved(
        String ticketId, String ticketBody, String status, List<KbChunk> chunks, PlanDecision nextStep, String error) {
}
