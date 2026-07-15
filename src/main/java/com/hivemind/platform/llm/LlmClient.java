package com.hivemind.platform.llm;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * Vertical-agnostic wrapper around the LangChain4j {@link ChatModel}. Every vertical talks to
 * Claude through this, never directly through LangChain4j types, so cost tracking and
 * observability can be added here once without touching agent code.
 */
@Component
public class LlmClient {

    private final ChatModel chatModel;

    public LlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String complete(String systemPrompt, String userMessage) {
        ChatResponse response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        return response.aiMessage().text();
    }
}
