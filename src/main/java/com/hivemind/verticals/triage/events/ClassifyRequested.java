package com.hivemind.verticals.triage.events;

/** Payload published to {@code hivemind.triage.classify} when a ticket is ingested. */
public record ClassifyRequested(String ticketId, String body) {
}
