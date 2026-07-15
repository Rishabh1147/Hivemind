package com.hivemind.platform.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Request-scoped state passed between agents handling a single request. {@code attributes} is
 * the hand-off point between agents in a pipeline (e.g. the classifier writes a category, the
 * retriever reads it) until the Kafka event bus replaces in-process hand-off with published
 * events.
 */
public class AgentContext {

    private final String requestId;
    private final Map<String, Object> attributes = new HashMap<>();

    public AgentContext(String requestId) {
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public Object get(String key) {
        return attributes.get(key);
    }
}
