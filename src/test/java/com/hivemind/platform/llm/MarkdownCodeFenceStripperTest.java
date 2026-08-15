package com.hivemind.platform.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownCodeFenceStripperTest {

    @Test
    void stripsFenceWithLanguageTag() {
        String fenced = "```json\n{\"category\": \"BILLING\", \"confidence\": 0.99}\n```";

        assertThat(MarkdownCodeFenceStripper.strip(fenced))
                .isEqualTo("{\"category\": \"BILLING\", \"confidence\": 0.99}");
    }

    @Test
    void stripsFenceWithoutLanguageTag() {
        String fenced = "```\n{\"answer\": \"hi\"}\n```";

        assertThat(MarkdownCodeFenceStripper.strip(fenced)).isEqualTo("{\"answer\": \"hi\"}");
    }

    @Test
    void leavesUnfencedJsonUnchanged() {
        String raw = "{\"category\": \"BUG\", \"confidence\": 0.8}";

        assertThat(MarkdownCodeFenceStripper.strip(raw)).isEqualTo(raw);
    }

    @Test
    void leavesTextWithOnlyAnOpeningFenceUnchanged() {
        String malformed = "```json\n{\"category\": \"BUG\"}";

        assertThat(MarkdownCodeFenceStripper.strip(malformed)).isEqualTo(malformed);
    }

    @Test
    void leavesTextShorterThanTwoFenceMarkersUnchanged() {
        assertThat(MarkdownCodeFenceStripper.strip("``")).isEqualTo("``");
    }
}
