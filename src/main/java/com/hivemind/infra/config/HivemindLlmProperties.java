package com.hivemind.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed replacement for what would otherwise be five-plus separate {@code @Value} parameters on
 * {@link ClaudeConfig} — crossed the "more than a couple of related {@code @Value}-driven
 * properties" line this codebase treats as the signal to switch, once {@code provider} and
 * {@code aws.region} joined {@code model}/{@code apiKey}/{@code maxTokens} for the Bedrock provider
 * switch. Scoped to what {@code ClaudeConfig} needs; {@code LlmClient} and {@code CostTracker} read
 * their own {@code hivemind.llm.*} values independently and aren't migrated here, since neither was
 * touched by this change.
 *
 * @param provider  {@code "anthropic"} (default) or {@code "bedrock"} — which {@code ChatModel}
 *                  implementation {@link ClaudeConfig} builds.
 * @param model     Model identifier. Format is provider-specific: a plain Anthropic model id
 *                  (e.g. {@code claude-haiku-4-5-20251001}) for {@code anthropic}, or a Bedrock
 *                  inference-profile id (e.g. {@code us.anthropic.claude-haiku-4-5-20251001-v1:0})
 *                  for {@code bedrock} — the two are not interchangeable.
 * @param apiKey    Anthropic API key. Only read when {@code provider=anthropic}; Bedrock
 *                  authenticates via the AWS SDK's own credential resolution (env vars), not this
 *                  field.
 * @param maxTokens Max output tokens per completion, both providers.
 * @param aws       Bedrock-only settings.
 */
@ConfigurationProperties(prefix = "hivemind.llm")
public record HivemindLlmProperties(String provider, String model, String apiKey, int maxTokens, Aws aws) {

    public record Aws(String region) {
    }
}
