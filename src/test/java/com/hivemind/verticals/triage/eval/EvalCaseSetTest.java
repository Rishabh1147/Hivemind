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
 * Validates the actual data files under {@code evals/triage/} — not the harness logic
 * ({@link TriageEvalScorerTest} already covers that in isolation — this instead catches the kind of
 * mistake only the real files can have: a typo'd id, a duplicate, a {@code mustCite} entry pointing
 * at a knowledge-base chunk that doesn't exist. None of this requires a live Claude call, unlike
 * actually running the harness, which is why it can be a real, always-on part of {@code mvn test}
 * rather than something gated behind the {@code eval} profile.
 */
class EvalCaseSetTest {

    private static final Path CASES_DIR = Path.of("evals", "triage");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void meetsTheFiftyPlusTargetFromDocsEvalsMd() throws IOException {
        assertThat(loadCases()).hasSizeGreaterThanOrEqualTo(50);
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
        try (Stream<Path> files = Files.list(CASES_DIR)) {
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
