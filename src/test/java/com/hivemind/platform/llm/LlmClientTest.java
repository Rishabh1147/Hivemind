package com.hivemind.platform.llm;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {

    // 1ms base backoff keeps retry tests fast; jitter is bounded by this value.
    private static final long TEST_BACKOFF_MS = 1;
    private static final CostTracker TEST_COST_TRACKER = new CostTracker(3.00, 15.00);

    /**
     * Overrides the actual provider call so the retry policy in {@link LlmClient#complete} can be
     * exercised without mocking LangChain4j's {@code ChatModel}/{@code ChatResponse} types.
     * {@link Tracer#NOOP} is a real no-op implementation (not a mock), the same choice this class
     * would make with no tracing backend configured.
     */
    private static class ScriptedLlmClient extends LlmClient {
        private final Queue<Object> script = new LinkedList<>();
        private int callCount = 0;

        ScriptedLlmClient(int maxAttempts) {
            super(null, TEST_COST_TRACKER, Tracer.NOOP, "claude-sonnet-5", maxAttempts, TEST_BACKOFF_MS);
        }

        void willThrow(RuntimeException e) {
            script.add(e);
        }

        void willReturn(String text) {
            // costUsd is a placeholder here — LlmClient.complete() recomputes and overwrites it via
            // CostTracker before returning, same as the real doChat() path.
            script.add(new LlmResponse(text, new TokenUsage(10, 20), 0.0));
        }

        int callCount() {
            return callCount;
        }

        @Override
        protected LlmResponse doChat(String systemPrompt, String userMessage) {
            callCount++;
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (LlmResponse) next;
        }
    }

    @Test
    void returnsTextOnFirstSuccess() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willReturn("hello");

        LlmResponse result = client.complete("system", "user");

        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.tokenUsage().inputTokenCount()).isEqualTo(10);
        assertThat(client.callCount()).isEqualTo(1);
        // 10 input tokens @ $3/M + 20 output tokens @ $15/M = 0.00003 + 0.0003
        assertThat(result.costUsd()).isEqualTo(0.00033);
    }

    @Test
    void retriesOnRetriableExceptionThenSucceeds() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willThrow(new RateLimitException("429"));
        client.willThrow(new RateLimitException("429"));
        client.willReturn("classified");

        LlmResponse result = client.complete("system", "user");

        assertThat(result.text()).isEqualTo("classified");
        assertThat(client.callCount()).isEqualTo(3);
    }

    @Test
    void throwsAfterExhaustingRetriesOnPersistentRetriableFailure() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willThrow(new RateLimitException("429"));
        client.willThrow(new RateLimitException("429"));
        client.willThrow(new RateLimitException("429"));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(RateLimitException.class);
        assertThat(client.callCount()).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetriableFailure() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willThrow(new AuthenticationException("invalid x-api-key"));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(AuthenticationException.class);
        assertThat(client.callCount()).isEqualTo(1);
    }
}
