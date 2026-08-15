package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.platform.llm.LlmResponse;
import org.springframework.stereotype.Component;

/**
 * LLM-as-judge tone scoring, split out from {@link TriageEvalScorer} because it's the one scoring
 * dimension that needs a real Claude call rather than a pure comparison — every other check
 * ({@code TriageEvalScorer}) is deliberately mock-free-testable equality/containment logic, and
 * mixing a real LLM call into that class would mean mocking {@code LlmClient} just to test the other
 * three dimensions. Not a {@code BaseAgent}/{@code @AgentRole}: this never runs in production, only
 * in the eval harness, so annotating it as a dispatchable pipeline agent would misrepresent it as
 * one.
 */
@Component
public class TriageEvalToneJudge {

    private static final String SYSTEM_PROMPT = """
            You are rating the tone of a customer support reply on a scale from 1 to 5: 1 means cold, \
            robotic, dismissive, or rude; 5 means warm, empathetic, professional, and clear. Judge \
            tone only, not factual correctness. Respond with ONLY a JSON object, no prose, in this \
            exact shape:
            {"score": <integer 1-5>}""";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public TriageEvalToneJudge(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public TriageEvalToneJudgment judge(String ticketBody, String answer) {
        if (answer == null) {
            return TriageEvalToneJudgment.notApplicable();
        }
        try {
            LlmResponse response = llmClient.complete(SYSTEM_PROMPT, buildUserMessage(ticketBody, answer));
            JsonNode node = objectMapper.readTree(response.text());
            return new TriageEvalToneJudgment(node.get("score").asInt(), response.costUsd(), null);
        } catch (Exception e) {
            return new TriageEvalToneJudgment(null, 0.0, "Tone judge call failed: " + e.getMessage());
        }
    }

    private String buildUserMessage(String ticketBody, String answer) {
        return "Customer ticket:\n" + ticketBody + "\n\nSupport reply:\n" + answer;
    }
}
