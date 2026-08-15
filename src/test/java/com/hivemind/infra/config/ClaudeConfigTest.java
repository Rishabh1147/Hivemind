package com.hivemind.infra.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean construction only — neither builder makes a network call, so both providers are verifiable
 * here without real credentials. The actual Bedrock wiring (real credentials, real Claude call) is
 * verified separately against the running app, not by this test.
 */
class ClaudeConfigTest {

    private final ClaudeConfig config = new ClaudeConfig();

    @Test
    void defaultsToAnthropicChatModel() {
        HivemindLlmProperties properties = new HivemindLlmProperties(
                "anthropic", "claude-haiku-4-5-20251001", "test-key", 1024, new HivemindLlmProperties.Aws("us-east-1"));

        ChatModel chatModel = config.claudeChatModel(properties);

        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void buildsBedrockChatModelWhenProviderIsBedrock() {
        HivemindLlmProperties properties = new HivemindLlmProperties(
                "bedrock", "us.anthropic.claude-haiku-4-5-20251001-v1:0", "", 1024,
                new HivemindLlmProperties.Aws("us-east-1"));

        ChatModel chatModel = config.claudeChatModel(properties);

        assertThat(chatModel).isInstanceOf(BedrockChatModel.class);
    }

    @Test
    void providerMatchIsCaseInsensitive() {
        HivemindLlmProperties properties = new HivemindLlmProperties(
                "BEDROCK", "us.anthropic.claude-haiku-4-5-20251001-v1:0", "", 1024,
                new HivemindLlmProperties.Aws("us-east-1"));

        ChatModel chatModel = config.claudeChatModel(properties);

        assertThat(chatModel).isInstanceOf(BedrockChatModel.class);
    }
}
