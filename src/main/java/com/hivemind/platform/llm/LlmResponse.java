package com.hivemind.platform.llm;

import dev.langchain4j.model.output.TokenUsage;

/**
 * What {@link LlmClient#complete} actually returns: the model's text, plus however many tokens it
 * cost to get it. Callers that only care about the text (every agent, today) just read
 * {@link #text()}; {@link CostTracker} is what turns {@link #tokenUsage()} into a dollar figure.
 */
public record LlmResponse(String text, TokenUsage tokenUsage) {
}
