package com.hivemind.verticals.triage.events;

import java.util.List;

/** Payload published to {@code hivemind.triage.responded}. */
public record TicketResponded(String ticketId, String status, String answer, List<String> citedChunkIds, String error) {
}
