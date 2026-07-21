package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.tool.ToolInvoker;
import com.hivemind.platform.tool.ToolRegistry;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.kb.KnowledgeBase;
import com.hivemind.verticals.triage.tools.SearchKbTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrieverAgentTest {

    private final ToolInvoker toolInvoker = new ToolInvoker(200, 3, 1);

    @Test
    void returnsMatchingChunksWhenSearchKbToolIsRegistered() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.get("searchKb")).thenReturn(Optional.of(new SearchKbTool(new KnowledgeBase())));
        RetrieverAgent agent = new RetrieverAgent(toolRegistry, toolInvoker, 3);

        AgentContext context = new AgentContext("ticket-1");
        context.put(TriageContextKeys.TICKET_BODY, "I was charged twice for my subscription");

        AgentResult<List<KbChunk>> result = agent.handle(context);

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).isNotEmpty();
        assertThat(result.payload().get(0).id()).isEqualTo("billing-duplicate-charge");
    }

    @Test
    void failsGracefullyWhenSearchKbToolIsNotRegistered() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.get("searchKb")).thenReturn(Optional.empty());
        RetrieverAgent agent = new RetrieverAgent(toolRegistry, toolInvoker, 3);

        AgentContext context = new AgentContext("ticket-2");
        context.put(TriageContextKeys.TICKET_BODY, "anything");

        AgentResult<List<KbChunk>> result = agent.handle(context);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not registered");
    }
}
