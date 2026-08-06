package com.hivemind.platform.llm;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostTrackerTest {

    private final CostTracker costTracker = new CostTracker(3.00, 15.00);

    @Test
    void computesCostFromInputAndOutputTokensSeparately() {
        // 1,000,000 input tokens @ $3/M + 1,000,000 output tokens @ $15/M = $3 + $15
        double cost = costTracker.costUsd(new TokenUsage(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(18.00);
    }

    @Test
    void returnsZeroForNullTokenUsage() {
        assertThat(costTracker.costUsd(null)).isEqualTo(0.0);
    }

    @Test
    void treatsNullInputOrOutputCountsAsZero() {
        TokenUsage outputOnly = new TokenUsage(null, 1_000_000);

        assertThat(costTracker.costUsd(outputOnly)).isEqualTo(15.00);
    }

    @Test
    void returnsZeroForZeroTokens() {
        assertThat(costTracker.costUsd(new TokenUsage(0, 0))).isEqualTo(0.0);
    }
}
