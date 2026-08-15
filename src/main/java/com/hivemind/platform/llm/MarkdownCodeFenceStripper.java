package com.hivemind.platform.llm;

/**
 * Claude sometimes wraps a requested-JSON-only completion in a markdown code fence
 * ({@code ```json ... ```}) despite an explicit "respond with ONLY a JSON object, no prose"
 * instruction — first observed 2026-08-15 via Bedrock, the first time any prompt in this codebase
 * had a real (non-auth-failing) completion to parse. Every strict-JSON prompt here
 * ({@code ClassifierAgent}, {@code ResponderAgent}) expects raw JSON, so {@link LlmClient} strips a
 * fence once, centrally, rather than each caller re-implementing the same defensive parsing.
 */
final class MarkdownCodeFenceStripper {

    private static final String FENCE = "```";

    private MarkdownCodeFenceStripper() {
    }

    static String strip(String text) {
        String trimmed = text.strip();
        if (trimmed.length() < FENCE.length() * 2
                || !trimmed.startsWith(FENCE)
                || !trimmed.endsWith(FENCE)) {
            return text;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline == -1) {
            return text;
        }
        String afterOpeningLine = trimmed.substring(firstNewline + 1);
        return afterOpeningLine.substring(0, afterOpeningLine.length() - FENCE.length()).strip();
    }
}
