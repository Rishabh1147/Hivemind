package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.platform.llm.LlmClient;
import com.hivemind.platform.llm.LlmResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriageEvalToneJudgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scoresToneFromWellFormedJudgeResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any()))
                .thenReturn(new LlmResponse("{\"score\": 5}", new TokenUsage(80, 5), 0.0002));
        TriageEvalToneJudge judge = new TriageEvalToneJudge(llmClient, objectMapper);

        TriageEvalToneJudgment judgment = judge.judge("I was charged twice", "Sorry about that — refunded now.");

        assertThat(judgment.score()).isEqualTo(5);
        assertThat(judgment.costUsd()).isEqualTo(0.0002);
        assertThat(judgment.error()).isNull();
    }

    @Test
    void isNotApplicableWhenNoAnswerWasDraftedAndNeverCallsTheLlm() {
        LlmClient llmClient = mock(LlmClient.class);
        TriageEvalToneJudge judge = new TriageEvalToneJudge(llmClient, objectMapper);

        TriageEvalToneJudgment judgment = judge.judge("multiple accounts sending spam", null);

        assertThat(judgment.score()).isNull();
        assertThat(judgment.error()).isNull();
        verify(llmClient, never()).complete(any(), any());
    }

    @Test
    void reportsErrorWhenLlmCallThrows() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenThrow(new RuntimeException("invalid x-api-key"));
        TriageEvalToneJudge judge = new TriageEvalToneJudge(llmClient, objectMapper);

        TriageEvalToneJudgment judgment = judge.judge("some ticket", "some answer");

        assertThat(judgment.score()).isNull();
        assertThat(judgment.error()).contains("Tone judge call failed");
    }

    @Test
    void reportsErrorOnMalformedJudgeResponse() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.complete(any(), any())).thenReturn(new LlmResponse("not json", new TokenUsage(5, 0), 0.0));
        TriageEvalToneJudge judge = new TriageEvalToneJudge(llmClient, objectMapper);

        TriageEvalToneJudgment judgment = judge.judge("some ticket", "some answer");

        assertThat(judgment.score()).isNull();
        assertThat(judgment.error()).contains("Tone judge call failed");
    }
}
