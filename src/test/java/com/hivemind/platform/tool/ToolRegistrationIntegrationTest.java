package com.hivemind.platform.tool;

import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.tools.SearchKbTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the whole tool-registration story with real Spring wiring, not mocks: a bean annotated
 * {@code @Tool} is discovered by {@link ToolRegistry} at context startup, and calling it through
 * {@link ToolInvoker} (the same path any future agent would use) returns real results.
 */
@SpringBootTest(properties = "hivemind.llm.api-key=test-key")
class ToolRegistrationIntegrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolInvoker toolInvoker;

    @Test
    void searchKbToolIsDiscoveredAndInvokableThroughTheRegistry() {
        assertThat(toolRegistry.names()).contains("searchKb");

        SearchKbTool tool = (SearchKbTool) toolRegistry.get("searchKb").orElseThrow();
        ToolResult<List<KbChunk>> result = toolInvoker.invoke("searchKb", () -> tool.search("charged twice", 3));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isNotEmpty();
        assertThat(result.payload().get(0).id()).isEqualTo("billing-duplicate-charge");
    }
}
