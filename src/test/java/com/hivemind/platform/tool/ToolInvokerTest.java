package com.hivemind.platform.tool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInvokerTest {

    @Test
    void returnsPayloadOnFirstSuccess() {
        ToolInvoker invoker = new ToolInvoker(200, 3, 1);

        ToolResult<String> result = invoker.invoke("dummy", () -> "ok");

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo("ok");
    }

    @Test
    void retriesOnTimeoutThenSucceeds() {
        ToolInvoker invoker = new ToolInvoker(50, 3, 1);
        AtomicInteger calls = new AtomicInteger();

        ToolResult<String> result = invoker.invoke("dummy", () -> {
            if (calls.incrementAndGet() < 2) {
                Thread.sleep(500);
            }
            return "ok-after-retry";
        });

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isEqualTo("ok-after-retry");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failsAfterExhaustingRetriesOnPersistentTimeout() {
        ToolInvoker invoker = new ToolInvoker(50, 2, 1);

        ToolResult<String> result = invoker.invoke("dummy", () -> {
            Thread.sleep(500);
            return "never";
        });

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("timed out after 2 attempts");
    }

    @Test
    void doesNotRetryOnNonTimeoutFailure() {
        ToolInvoker invoker = new ToolInvoker(200, 3, 1);
        AtomicInteger calls = new AtomicInteger();

        ToolResult<String> result = invoker.invoke("dummy", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("boom");
        assertThat(calls.get()).isEqualTo(1);
    }
}
