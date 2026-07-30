package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.kb.KbChunk;

import java.util.List;

/**
 * Payload published to {@code hivemind.triage.retrieved}. Carries {@code ticketBody} from the
 * start this time — {@code TicketClassified} had to be split out of {@code TriageResponse}
 * specifically because it didn't carry the body a downstream consumer needed; same reasoning
 * applied up front here instead of rediscovering the gap when {@code ResponderAgent} needed it.
 */
public record TicketRetrieved(String ticketId, String ticketBody, String status, List<KbChunk> chunks, String error) {
}
