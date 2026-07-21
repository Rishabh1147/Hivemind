package com.hivemind.verticals.triage.agents;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.agent.AgentRole;
import com.hivemind.platform.agent.BaseAgent;
import com.hivemind.platform.tool.ToolInvoker;
import com.hivemind.platform.tool.ToolRegistry;
import com.hivemind.platform.tool.ToolResult;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.tools.SearchKbTool;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * Looks up {@code searchKb} by name from {@link ToolRegistry} rather than depending on
 * {@link SearchKbTool} directly, and calls it through {@link ToolInvoker} for the timeout/retry/
 * sandbox handling — the same path any future planner-dispatched agent would use, not a shortcut
 * specific to this one tool.
 *
 * <p>The registry returns {@code Object}, so this cast from "a bean named 'searchKb'" to the
 * concrete {@code SearchKbTool} type is a real type-safety gap, not an oversight — closing it
 * properly means every tool implementing a common, generically-invokable contract, which is worth
 * designing once there's a second tool with a different method signature to design it against, not
 * on spec for one tool today.
 */
@AgentRole(vertical = "triage", role = "retriever")
public class RetrieverAgent extends BaseAgent<List<KbChunk>> {

    private static final String SEARCH_KB_TOOL_NAME = "searchKb";

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final int topK;

    public RetrieverAgent(
            ToolRegistry toolRegistry,
            ToolInvoker toolInvoker,
            @Value("${hivemind.triage.retriever.top-k:3}") int topK) {
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.topK = topK;
    }

    @Override
    public AgentResult<List<KbChunk>> handle(AgentContext context) {
        String ticketBody = (String) context.get(TriageContextKeys.TICKET_BODY);

        Object tool = toolRegistry.get(SEARCH_KB_TOOL_NAME).orElse(null);
        if (!(tool instanceof SearchKbTool searchKbTool)) {
            return AgentResult.failure("Tool '" + SEARCH_KB_TOOL_NAME + "' is not registered");
        }

        ToolResult<List<KbChunk>> result =
                toolInvoker.invoke(SEARCH_KB_TOOL_NAME, () -> searchKbTool.search(ticketBody, topK));

        return result.success() ? AgentResult.success(result.payload()) : AgentResult.failure(result.errorMessage());
    }
}
