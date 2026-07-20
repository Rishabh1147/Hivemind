package com.hivemind.verticals.triage.tools;

import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.kb.KnowledgeBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKbToolTest {

    private final SearchKbTool tool = new SearchKbTool(new KnowledgeBase());

    @Test
    void returnsChunksMatchingQueryTerms() {
        List<KbChunk> results = tool.search("charged twice billing", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).id()).isEqualTo("billing-duplicate-charge");
    }

    @Test
    void respectsTopK() {
        List<KbChunk> results = tool.search("billing payment charge report bug feature abuse", 2);

        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        List<KbChunk> results = tool.search("xylophone quantum zeppelin", 5);

        assertThat(results).isEmpty();
    }
}
