package com.hivemind.platform.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JitteredExponentialBackoffTest {

    @Test
    void delayStaysWithinTheExponentialCeilingForEachAttempt() {
        long baseDelayMillis = 100;
        for (int attempt = 1; attempt <= 5; attempt++) {
            long ceiling = baseDelayMillis * (1L << (attempt - 1));
            long delay = JitteredExponentialBackoff.computeDelayMillis(baseDelayMillis, attempt);
            assertThat(delay).isBetween(0L, ceiling);
        }
    }
}
