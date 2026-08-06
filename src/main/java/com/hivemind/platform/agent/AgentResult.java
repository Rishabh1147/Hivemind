package com.hivemind.platform.agent;

/**
 * {@code costUsd} is 0.0 for every agent that doesn't call an LLM (today: {@code RetrieverAgent},
 * {@code RoutingAgent}) — only an agent that actually got token counts back from
 * {@code LlmClient} reports a real figure, via the {@link #success(Object, double)} overload.
 */
public record AgentResult<T>(boolean success, T payload, String errorMessage, double costUsd) {

    public static <T> AgentResult<T> success(T payload) {
        return new AgentResult<>(true, payload, null, 0.0);
    }

    public static <T> AgentResult<T> success(T payload, double costUsd) {
        return new AgentResult<>(true, payload, null, costUsd);
    }

    public static <T> AgentResult<T> failure(String errorMessage) {
        return new AgentResult<>(false, null, errorMessage, 0.0);
    }
}
