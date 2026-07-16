package com.hivemind.platform.llm;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Vertical-agnostic wrapper around the LangChain4j {@link ChatModel}. Every vertical talks to
 * Claude through this, never directly through LangChain4j types, so cost tracking and
 * observability can be added here once without touching agent code.
 *
 * <p>Retries transient provider failures ({@link RetriableException} — rate limits, 5xx) with
 * exponential backoff and full jitter, up to {@code maxAttempts}. Anything else (auth errors,
 * malformed requests) is not retriable and propagates immediately.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ChatModel chatModel;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public LlmClient(
            ChatModel chatModel,
            @Value("${hivemind.llm.retry.max-attempts:3}") int maxAttempts,
            @Value("${hivemind.llm.retry.base-backoff-ms:500}") long baseBackoffMs) {
        this.chatModel = chatModel;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public String complete(String systemPrompt, String userMessage) {
        RetriableException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doChat(systemPrompt, userMessage);
            } catch (RetriableException e) {
                lastFailure = e;
                if (attempt == maxAttempts) {
                    break;
                }
                long backoffMs = backoffWithFullJitter(attempt);
                log.warn("Claude call failed with a retriable error (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxAttempts, backoffMs, e.getMessage());
                sleep(backoffMs);
            }
        }
        throw lastFailure;
    }

    /**
     * The actual provider call, isolated behind its own method so the retry policy above can be
     * unit-tested (via a subclass override) without needing to mock LangChain4j's response types.
     */
    protected String doChat(String systemPrompt, String userMessage) {
        ChatResponse response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        return response.aiMessage().text();
    }

    private long backoffWithFullJitter(int attempt) {
        long exponential = baseBackoffMs * (1L << (attempt - 1));
        return ThreadLocalRandom.current().nextLong(exponential + 1);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before retry", e);
        }
    }
}
