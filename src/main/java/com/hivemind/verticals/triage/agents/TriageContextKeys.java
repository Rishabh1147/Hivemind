package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;

/**
 * {@link AgentContext} attribute keys shared across triage agents. Extracted out of
 * {@code ClassifierAgent} once {@code RetrieverAgent} became a second real reader of the same
 * ticket-body key — same "wait for a second real user" discipline as
 * {@code JitteredExponentialBackoff}'s extraction out of {@code LlmClient}.
 */
public final class TriageContextKeys {

    public static final String TICKET_BODY = "ticketBody";

    private TriageContextKeys() {
    }
}
