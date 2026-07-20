package com.hivemind.platform.retry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter: a random delay in {@code [0, baseDelayMillis * 2^(attempt-1)]}
 * rather than the exponential value itself, so concurrent retries after the same failure spread out
 * instead of re-synchronizing (a thundering herd against a provider that's already struggling).
 *
 * <p>Extracted from {@code LlmClient} once {@code ToolInvoker} needed the identical algorithm for
 * tool-call timeouts — the same "wait for a second real user before generalizing" discipline
 * applied elsewhere in this codebase, just now with that second user in hand.
 */
public final class JitteredExponentialBackoff {

    private JitteredExponentialBackoff() {
    }

    public static long computeDelayMillis(long baseDelayMillis, int attempt) {
        long exponentialCeiling = baseDelayMillis * (1L << (attempt - 1));
        return ThreadLocalRandom.current().nextLong(exponentialCeiling + 1);
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before retry", e);
        }
    }
}
