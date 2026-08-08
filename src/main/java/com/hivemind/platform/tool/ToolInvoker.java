package com.hivemind.platform.tool;

import com.hivemind.platform.retry.JitteredExponentialBackoff;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tool calls sandboxed on their own virtual thread with a timeout, retrying only on timeout
 * (a transient, infra-level failure) with exponential backoff and full jitter — never on an
 * exception the tool itself threw, since that's a logic error retrying can't fix. Same shape as
 * {@link com.hivemind.platform.llm.LlmClient}'s retriable/non-retriable split, adapted for tools
 * that don't have LangChain4j's exception hierarchy to lean on.
 *
 * <p>"Resource caps" beyond the per-call timeout (ARCHITECTURE.md mentions them) are deliberately
 * not built yet — no concrete tool needs them, and a number picked without one would be a guess.
 *
 * <p>{@link #invoke} opens one {@code tool.invoke} span per logical call, covering every retry
 * attempt underneath it — the same one-span-per-operation choice {@code LlmClient.complete()} makes.
 * Tags {@code tool.name}, {@code tool.success}, and {@code tool.retry_count} (attempts beyond the
 * first). No {@code span.error(...)} call anywhere here: {@code invoke} never throws — every failure,
 * including the ones caught below, comes back as {@code ToolResult.failure(...)}, so there's no
 * exception that ever escapes the span's scope to mark. No {@code tool.vertical} tag either, for the
 * same reason {@code LlmClient} has no {@code llm.vertical}: nothing plumbs a vertical into this
 * class today, and only one vertical calls in.
 */
@Component
public class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);

    private final ExecutorService sandbox = Executors.newVirtualThreadPerTaskExecutor();
    private final Tracer tracer;
    private final long timeoutMs;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public ToolInvoker(
            Tracer tracer,
            @Value("${hivemind.tool.timeout-ms:5000}") long timeoutMs,
            @Value("${hivemind.tool.retry.max-attempts:3}") int maxAttempts,
            @Value("${hivemind.tool.retry.base-backoff-ms:200}") long baseBackoffMs) {
        this.tracer = tracer;
        this.timeoutMs = timeoutMs;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public <T> ToolResult<T> invoke(String toolName, Callable<T> operation) {
        Span span = tracer.nextSpan().name("tool.invoke " + toolName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("tool.name", toolName);
            Attempt<T> attempt = attemptWithRetry(toolName, operation);
            span.tag("tool.success", String.valueOf(attempt.result().success()));
            span.tag("tool.retry_count", String.valueOf(attempt.count() - 1));
            return attempt.result();
        } finally {
            span.end();
        }
    }

    private record Attempt<T>(ToolResult<T> result, int count) {
    }

    private <T> Attempt<T> attemptWithRetry(String toolName, Callable<T> operation) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<T> future = sandbox.submit(operation);
            try {
                return new Attempt<>(ToolResult.success(future.get(timeoutMs, TimeUnit.MILLISECONDS)), attempt);
            } catch (TimeoutException e) {
                future.cancel(true);
                if (attempt == maxAttempts) {
                    return new Attempt<>(
                            ToolResult.failure("Tool " + toolName + " timed out after " + maxAttempts + " attempts"),
                            attempt);
                }
                long backoffMs = JitteredExponentialBackoff.computeDelayMillis(baseBackoffMs, attempt);
                log.warn("Tool {} timed out (attempt {}/{}), retrying in {}ms", toolName, attempt, maxAttempts, backoffMs);
                JitteredExponentialBackoff.sleep(backoffMs);
            } catch (ExecutionException e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return new Attempt<>(ToolResult.failure("Tool " + toolName + " failed: " + cause), attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Attempt<>(ToolResult.failure("Tool " + toolName + " invocation interrupted"), attempt);
            }
        }
        return new Attempt<>(
                ToolResult.failure("Tool " + toolName + " timed out after " + maxAttempts + " attempts"), maxAttempts);
    }

    @PreDestroy
    void shutdown() {
        sandbox.shutdown();
    }
}
