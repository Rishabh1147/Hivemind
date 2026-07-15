package com.hivemind.infra.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClaudeConfig {

    @Bean
    public ChatModel claudeChatModel(
            @Value("${hivemind.llm.api-key}") String apiKey,
            @Value("${hivemind.llm.model}") String modelName,
            @Value("${hivemind.llm.max-tokens}") int maxTokens) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }
}
