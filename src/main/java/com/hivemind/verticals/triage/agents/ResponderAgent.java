package com.hivemind.verticals.triage.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.agent.AgentRole;
import com.hivemind.platform.agent.BaseAgent;
import com.hivemind.platform.llm.CostTracker;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.platform.llm.LlmResponse;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.model.DraftResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Drafts a reply grounded in the chunks {@code RetrieverAgent} found, the same strict-JSON-via-
 * prompt approach as {@code ClassifierAgent} (see {@code INTERVIEW_PREP.md} for why: simplest thing
 * that works today, tool-calling/structured output is the upgrade once there's a concrete reason).
 * Works with zero retrieved chunks too — not every ticket matches the knowledge base, and a generic
 * "no specific policy found" reply is a legitimate real answer, not a failure.
 */
@AgentRole(vertical = "triage", role = "responder")
public class ResponderAgent extends BaseAgent<DraftResponse> {

    private static final String SYSTEM_PROMPT = """
            You are a support assistant drafting a reply to a customer ticket using knowledge base \
            excerpts. Write a concise, helpful reply grounded only in the excerpts provided — if \
            they don't cover the ticket, say so rather than inventing policy details.
            Respond with ONLY a JSON object, no prose, in this exact shape:
            {"answer": "<the reply text>", "citedChunkIds": ["<kb chunk id>", ...]}""";

    private final LlmClient llmClient;
    private final CostTracker costTracker;
    private final ObjectMapper objectMapper;

    public ResponderAgent(LlmClient llmClient, CostTracker costTracker, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.costTracker = costTracker;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult<DraftResponse> handle(AgentContext context) {
        String ticketBody = (String) context.get(TriageContextKeys.TICKET_BODY);
        List<KbChunk> chunks = (List<KbChunk>) context.get(TriageContextKeys.RETRIEVED_CHUNKS);

        LlmResponse response;
        try {
            response = llmClient.complete(SYSTEM_PROMPT, buildUserMessage(ticketBody, chunks));
        } catch (Exception e) {
            return AgentResult.failure("Responder LLM call failed: " + e.getMessage());
        }
        try {
            JsonNode node = objectMapper.readTree(response.text());
            String answer = node.get("answer").asText();
            List<String> citedChunkIds = new ArrayList<>();
            node.get("citedChunkIds").forEach(idNode -> citedChunkIds.add(idNode.asText()));
            double costUsd = costTracker.costUsd(response.tokenUsage());
            return AgentResult.success(new DraftResponse(answer, citedChunkIds), costUsd);
        } catch (Exception e) {
            return AgentResult.failure("Failed to parse responder response: " + e.getMessage());
        }
    }

    private String buildUserMessage(String ticketBody, List<KbChunk> chunks) {
        StringBuilder message = new StringBuilder("Ticket:\n").append(ticketBody).append("\n\n");
        if (chunks.isEmpty()) {
            message.append("Knowledge base excerpts: none matched this ticket.");
        } else {
            message.append("Knowledge base excerpts:\n");
            for (KbChunk chunk : chunks) {
                message.append("- [").append(chunk.id()).append("] ")
                        .append(chunk.title()).append(": ").append(chunk.text()).append('\n');
            }
        }
        return message.toString();
    }
}
