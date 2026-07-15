package com.hivemind.platform.agent;

/**
 * Base class for all agents across all verticals. Vertical-agnostic on purpose: it knows nothing
 * about triage, tickets, or categories — only how to accept an {@link AgentContext} and produce
 * an {@link AgentResult}.
 */
public abstract class BaseAgent<T> {

    public abstract AgentResult<T> handle(AgentContext context);
}
