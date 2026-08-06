package com.hivemind.platform.llm;

import dev.langchain4j.model.output.TokenUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link TokenUsage} into a USD figure, at a configurable per-million-token input/output
 * rate ({@code hivemind.llm.pricing.*} in {@code application.yml}). The configured rates are
 * placeholders, not verified published pricing for {@code hivemind.llm.model} — update them to the
 * real published rate before trusting the dollar figures this produces for anything beyond
 * exercising the eval harness's cost-per-ticket gate. The mechanism (extracting real token counts
 * from a real {@link dev.langchain4j.model.chat.response.ChatResponse} and turning them into a
 * number) is what's actually being demonstrated here, not the specific price.
 */
@Component
public class CostTracker {

    private final double inputCostPerMillionTokens;
    private final double outputCostPerMillionTokens;

    public CostTracker(
            @Value("${hivemind.llm.pricing.input-cost-per-million-tokens}") double inputCostPerMillionTokens,
            @Value("${hivemind.llm.pricing.output-cost-per-million-tokens}") double outputCostPerMillionTokens) {
        this.inputCostPerMillionTokens = inputCostPerMillionTokens;
        this.outputCostPerMillionTokens = outputCostPerMillionTokens;
    }

    public double costUsd(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return 0.0;
        }
        int inputTokens = orZero(tokenUsage.inputTokenCount());
        int outputTokens = orZero(tokenUsage.outputTokenCount());
        return (inputTokens * inputCostPerMillionTokens + outputTokens * outputCostPerMillionTokens) / 1_000_000.0;
    }

    private int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
