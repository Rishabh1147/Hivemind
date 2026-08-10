package com.hivemind.verticals.triage.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.verticals.triage.kb.KbChunk;
import com.hivemind.verticals.triage.kb.KnowledgeBase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the data files under {@code evals/triage-adversarial/} — the separate, ungated 20-case
 * set {@code docs/EVALS.md} describes (prompt injection, contradictory ticket content, multi-issue
 * tickets, non-English text, no-relevant-KB-context grounding checks). Same structural checks as
 * {@link EvalCaseSetTest} runs against the primary set; a distinct test class rather than a
 * parameterized shared one because the two sets have different size expectations (exactly 20 here,
 * 50+ for the primary set) and are conceptually different things — one is CI-gated, this one is
 * tracked-not-gated — even though the underlying {@link TriageEvalCase} schema is identical.
 */
class AdversarialEvalCaseSetTest {

    private static final Path CASES_DIR = Path.of("evals", "triage-adversarial");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void hasExactlyTwentyCasesPerDocsEvalsMd() throws IOException {
        assertThat(loadCases()).hasSize(20);
    }

    @Test
    void everyCaseIdIsUnique() throws IOException {
        List<TriageEvalCase> cases = loadCases();
        Set<String> uniqueIds = new HashSet<>();
        for (TriageEvalCase evalCase : cases) {
            assertThat(uniqueIds.add(evalCase.id()))
                    .as("duplicate eval case id: %s", evalCase.id())
                    .isTrue();
        }
    }

    @Test
    void noCaseIdCollidesWithThePrimaryCaseSet() throws IOException {
        Set<String> adversarialIds = loadCases().stream().map(TriageEvalCase::id).collect(Collectors.toSet());
        Set<String> primaryIds = loadPrimaryCaseIds();

        assertThat(adversarialIds)
                .as("adversarial case ids must not collide with evals/triage/ ids")
                .doesNotContainAnyElementsOf(primaryIds);
    }

    @Test
    void everyMustCiteEntryReferencesARealKnowledgeBaseChunk() throws IOException {
        Set<String> realChunkIds = new KnowledgeBase().all().stream()
                .map(KbChunk::id)
                .collect(Collectors.toSet());

        for (TriageEvalCase evalCase : loadCases()) {
            for (String citedId : evalCase.mustCite()) {
                assertThat(realChunkIds)
                        .as("case %s cites unknown KB chunk '%s'", evalCase.id(), citedId)
                        .contains(citedId);
            }
        }
    }

    @Test
    void everyCaseHasANonBlankTicketBody() throws IOException {
        for (TriageEvalCase evalCase : loadCases()) {
            assertThat(evalCase.ticket()).as("case %s", evalCase.id()).isNotBlank();
        }
    }

    private List<TriageEvalCase> loadCases() throws IOException {
        return loadCases(CASES_DIR);
    }

    private Set<String> loadPrimaryCaseIds() throws IOException {
        return loadCases(Path.of("evals", "triage")).stream().map(TriageEvalCase::id).collect(Collectors.toSet());
    }

    private List<TriageEvalCase> loadCases(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(this::readCase)
                    .toList();
        }
    }

    private TriageEvalCase readCase(Path file) {
        try {
            return OBJECT_MAPPER.readValue(file.toFile(), TriageEvalCase.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse eval case file " + file, e);
        }
    }
}
