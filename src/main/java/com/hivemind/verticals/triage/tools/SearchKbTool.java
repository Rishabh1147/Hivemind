package com.hivemind.verticals.triage.tools;

import com.hivemind.platform.tool.Tool;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.kb.KnowledgeBase;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * First real tool, registered via {@link Tool} and called through {@code ToolInvoker} rather than
 * directly, so it gets the same timeout/retry/sandbox handling any future tool gets for free.
 *
 * <p>Scoring is naive keyword overlap, not the hybrid BM25 + pgvector retrieval
 * {@code ARCHITECTURE.md} describes — deliberately the simplest thing that returns relevant chunks
 * today; upgrading the scoring later doesn't change this tool's registration or invocation path.
 */
@Tool(vertical = "triage", name = "searchKb")
public class SearchKbTool {

    private final KnowledgeBase knowledgeBase;

    public SearchKbTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public List<KbChunk> search(String query, int topK) {
        Set<String> queryTerms = tokenize(query);
        return knowledgeBase.all().stream()
                .map(chunk -> Map.entry(chunk, overlapScore(queryTerms, tokenize(chunk.title() + " " + chunk.text()))))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<KbChunk, Integer>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(term -> !term.isBlank())
                .collect(Collectors.toSet());
    }

    private int overlapScore(Set<String> queryTerms, Set<String> chunkTerms) {
        Set<String> intersection = new HashSet<>(queryTerms);
        intersection.retainAll(chunkTerms);
        return intersection.size();
    }
}
