package com.hivemind.platform.llm;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {

    // 1ms base backoff keeps retry tests fast; jitter is bounded by this value.
    private static final long TEST_BACKOFF_MS = 1;

    /**
     * Overrides the actual provider call so the retry policy in {@link LlmClient#complete} can be
     * exercised without mocking LangChain4j's {@code ChatModel}/{@code ChatResponse} types.
     */
    private static class ScriptedLlmClient extends LlmClient {
        private final Queue<Object> script = new LinkedList<>();
        private int callCount = 0;

        ScriptedLlmClient(int maxAttempts) {
            super(null, maxAttempts, TEST_BACKOFF_MS);
        }

        void willThrow(RuntimeException e) {
            script.add(e);
        }

        void willReturn(String text) {
            script.add(text);
        }

        int callCount() {
            return callCount;
        }

        @Override
        protected String doChat(String systemPrompt, String userMessage) {
            callCount++;
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        }
    }

    @Test
    void returnsTextOnFirstSuccess() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willReturn("hello");

        String result = client.complete("system", "user");

        assertThat(result).isEqualTo("hello");
        assertThat(client.callCount()).isEqualTo(1);
    }

    @Test
    void retriesOnRetriableExceptionThenSucceeds() {
        ScriptedLlmClient client = new ScriptedLlmClient(3);
        client.willThrow(new RateLimitException("429"));
        client.willThrow(new RateLimitException("429"));
        client.willReturn("classified");

        String result = client.complete("system", "user");

        assertThat(result).isEqualTo("classified");
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
