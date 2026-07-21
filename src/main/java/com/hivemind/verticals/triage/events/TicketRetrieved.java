package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.kb.KbChunk;

import java.util.List;

/** Payload published to {@code hivemind.triage.retrieved}. */
public record TicketRetrieved(String ticketId, String status, List<KbChunk> chunks, String error) {
}
