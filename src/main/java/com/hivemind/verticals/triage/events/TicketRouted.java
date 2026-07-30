package com.hivemind.verticals.triage.events;

import com.hivemind.verticals.triage.model.RoutingDecision;

/** Payload published to {@code hivemind.triage.routed} — the final event in the pipeline today. */
public record TicketRouted(String ticketId, String status, RoutingDecision routingDecision, String error) {
}
