package com.hivemind.platform.llm;

import dev.langchain4j.model.output.TokenUsage;

/**
 * What {@link LlmClient#complete} actually returns: the model's text, however many tokens it cost to
 * get it, and the dollar figure {@link CostTracker} computed from those tokens. {@link LlmClient}
 * fills in {@link #costUsd()} itself once the call succeeds — callers never call {@link CostTracker}
 * directly, so the conversion happens in exactly one place.
 */
public record LlmResponse(String text, TokenUsage tokenUsage, double costUsd) {

    LlmResponse withCost(double costUsd) {
        return new LlmResponse(text, tokenUsage, costUsd);
    }
}
