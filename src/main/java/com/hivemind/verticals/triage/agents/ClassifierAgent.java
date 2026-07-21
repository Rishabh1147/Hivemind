package com.hivemind.verticals.triage.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.platform.agent.AgentRole;
import com.hivemind.platform.agent.BaseAgent;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.Classification;

@AgentRole(vertical = "triage", role = "classifier")
public class ClassifierAgent extends BaseAgent<Classification> {

    private static final String SYSTEM_PROMPT = """
            You are a support ticket classifier for a SaaS product. Classify the ticket into \
            exactly one of: BILLING, BUG, FEATURE_REQUEST, ABUSE, OTHER.
            Respond with ONLY a JSON object, no prose, in this exact shape:
            {"category": "<ONE_OF_THE_ABOVE>", "confidence": <number between 0 and 1>}""";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ClassifierAgent(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentResult<Classification> handle(AgentContext context) {
        String ticketBody = (String) context.get(TriageContextKeys.TICKET_BODY);
        String raw;
        try {
            raw = llmClient.complete(SYSTEM_PROMPT, ticketBody);
        } catch (Exception e) {
            return AgentResult.failure("Classifier LLM call failed: " + e.getMessage());
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            Category category = Category.valueOf(node.get("category").asText());
            double confidence = node.get("confidence").asDouble();
            return AgentResult.success(new Classification(category, confidence));
        } catch (Exception e) {
            return AgentResult.failure("Failed to parse classifier response: " + e.getMessage());
        }
    }
}
