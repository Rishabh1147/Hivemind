package com.hivemind.platform.agent;

public record AgentResult<T>(boolean success, T payload, String errorMessage) {

    public static <T> AgentResult<T> success(T payload) {
        return new AgentResult<>(true, payload, null);
    }

    public static <T> AgentResult<T> failure(String errorMessage) {
        return new AgentResult<>(false, null, errorMessage);
    }
}
