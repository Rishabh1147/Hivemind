package com.hivemind.verticals.triage.model;

import java.util.List;

/** {@code ResponderAgent} output: a drafted reply grounded in the cited knowledge-base chunks. */
public record DraftResponse(String answer, List<String> citedChunkIds) {
}
