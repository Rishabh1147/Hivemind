package com.hivemind.verticals.triage.model;

/**
 * {@code PlannerAgent}'s output — whether the pipeline should draft a customer-facing response for
 * this ticket at all. Retrieval always runs regardless of this decision (grounding is still worth
 * having on record even when nothing gets drafted from it); only the response-drafting Claude call
 * is what this gates.
 */
public enum PlanDecision {
    DRAFT_RESPONSE,
    SKIP_RESPONSE
}
