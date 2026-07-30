package com.hivemind.verticals.triage.model;

/** {@code RoutingAgent} output — the final Route step from {@code ARCHITECTURE.md}'s pipeline. */
public enum RoutingDecision {
    AUTO_RESOLVE,
    QUEUE_FOR_HUMAN,
    ESCALATE
}
