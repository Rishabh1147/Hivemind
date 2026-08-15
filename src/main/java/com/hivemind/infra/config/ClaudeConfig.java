package com.hivemind.infra.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

/**
 * Builds the one {@link ChatModel} bean every vertical talks to through {@code LlmClient} — see
 * {@code LlmClient}'s own Javadoc for why nothing downstream ever sees a provider-specific type
 * directly. {@code hivemind.llm.provider} picks which concrete provider backs it; {@code anthropic}
 * (the default, unchanged from every prior session) talks straight to api.anthropic.com,
 * {@code bedrock} (added session 21, once a real, verified AWS Bedrock key existed to build against)
 * routes the same Claude models through AWS instead — useful when Anthropic-direct credits are
 * unfunded but AWS credits aren't, which is exactly the situation that motivated adding it.
 *
 * <p>{@code BedrockChatModel} performs its own internal retry by default (2 attempts) before
 * mapping a final failure into the same {@code dev.langchain4j.exception.RetriableException}
 * hierarchy {@code LlmClient}'s retry loop already catches (verified against the 1.17.2 source:
 * {@code BedrockExceptionMapper} delegates to the shared core mapper every other provider uses —
 * 429/5xx/408 all become {@code RetriableException} subclasses). {@code maxRetries(0)} below turns
 * that internal retry off so {@code LlmClient} stays the single place a retry policy is configured,
 * rather than two independent retry loops silently multiplying attempts on a persistent failure.
 */
@Configuration
@EnableConfigurationProperties(HivemindLlmProperties.class)
public class ClaudeConfig {

    @Bean
    public ChatModel claudeChatModel(HivemindLlmProperties properties) {
        return "bedrock".equalsIgnoreCase(properties.provider())
                ? bedrockChatModel(properties)
                : anthropicChatModel(properties);
    }

    private ChatModel anthropicChatModel(HivemindLlmProperties properties) {
        return AnthropicChatModel.builder()
                .apiKey(properties.apiKey())
                .modelName(properties.model())
                .maxTokens(properties.maxTokens())
                .build();
    }

    private ChatModel bedrockChatModel(HivemindLlmProperties properties) {
        return BedrockChatModel.builder()
                .region(Region.of(properties.aws().region()))
                .modelId(properties.model())
                .maxRetries(0)
                .build();
    }
}
