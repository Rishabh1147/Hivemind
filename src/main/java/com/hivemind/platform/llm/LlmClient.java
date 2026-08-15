package com.hivemind.platform.llm;

import com.hivemind.platform.retry.JitteredExponentialBackoff;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Vertical-agnostic wrapper around the LangChain4j {@link ChatModel}. Every vertical talks to
 * Claude through this, never directly through LangChain4j types, so a retry policy, real
 * token-usage-derived cost via {@link CostTracker}, the {@code llm.*} tracing span, and stripping an
 * occasional markdown code fence off strict-JSON completions ({@link MarkdownCodeFenceStripper}) all
 * exist in exactly one place instead of being re-derived per agent.
 *
 * <p>Retries transient provider failures ({@link RetriableException} — rate limits, 5xx) with
 * exponential backoff and full jitter, up to {@code maxAttempts}. Anything else (auth errors,
 * malformed requests) is not retriable and propagates immediately.
 *
 * <p>Each {@link #complete} call opens one span covering every retry attempt — a caller thinks of
 * "classify this ticket" as one logical LLM call, not {@code maxAttempts} of them — the same
 * one-span-per-logical-operation choice {@code EventBus#publish} makes for a Kafka publish. There's
 * no {@code llm.vertical} tag: unlike {@code EventBus}, which reads the vertical straight out of the
 * topic name it's already given, this class has no vertical concept plumbed into it at all (nothing
 * downstream needs it yet with only one vertical calling in) — add it if a second vertical ever makes
 * that gap real instead of hypothetical.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ChatModel chatModel;
    private final CostTracker costTracker;
    private final Tracer tracer;
    private final String modelName;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public LlmClient(
            ChatModel chatModel,
            CostTracker costTracker,
            Tracer tracer,
            @Value("${hivemind.llm.model}") String modelName,
            @Value("${hivemind.llm.retry.max-attempts:3}") int maxAttempts,
            @Value("${hivemind.llm.retry.base-backoff-ms:500}") long baseBackoffMs) {
        this.chatModel = chatModel;
        this.costTracker = costTracker;
        this.tracer = tracer;
        this.modelName = modelName;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public LlmResponse complete(String systemPrompt, String userMessage) {
        Span span = tracer.nextSpan().name("llm.complete").start();
        long startNanos = System.nanoTime();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("llm.model", modelName);
            LlmResponse response = withCost(attemptWithRetry(systemPrompt, userMessage));
            tagUsage(span, response);
            return response;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.tag("llm.latency_ms", String.valueOf((System.nanoTime() - startNanos) / 1_000_000));
            span.end();
        }
    }

    private LlmResponse attemptWithRetry(String systemPrompt, String userMessage) {
        RetriableException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doChat(systemPrompt, userMessage);
            } catch (RetriableException e) {
                lastFailure = e;
                if (attempt == maxAttempts) {
                    break;
                }
                long backoffMs = JitteredExponentialBackoff.computeDelayMillis(baseBackoffMs, attempt);
                log.warn("Claude call failed with a retriable error (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxAttempts, backoffMs, e.getMessage());
                JitteredExponentialBackoff.sleep(backoffMs);
            }
        }
        throw lastFailure;
    }

    private LlmResponse withCost(LlmResponse response) {
        return response.withCost(costTracker.costUsd(response.tokenUsage()));
    }

    private void tagUsage(Span span, LlmResponse response) {
        TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            return;
        }
        span.tag("llm.input_tokens", String.valueOf(Objects.requireNonNullElse(usage.inputTokenCount(), 0)));
        span.tag("llm.output_tokens", String.valueOf(Objects.requireNonNullElse(usage.outputTokenCount(), 0)));
        span.tag("llm.cost_usd", String.valueOf(response.costUsd()));
    }

    /**
     * The actual provider call, isolated behind its own method so the retry policy above can be
     * unit-tested (via a subclass override) without needing to mock LangChain4j's response types.
     */
    protected LlmResponse doChat(String systemPrompt, String userMessage) {
        ChatResponse response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        String text = MarkdownCodeFenceStripper.strip(response.aiMessage().text());
        return new LlmResponse(text, response.tokenUsage(), 0.0);
    }

}
