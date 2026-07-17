package com.hivemind.verticals.triage.messaging;

/**
 * Topic names for the triage vertical, following the {@code hivemind.<vertical>.<stage>}
 * convention (see {@code TopicNamingTest}). Declared as literals rather than built from
 * {@code TopicNaming.of(...)} because {@code @KafkaListener(topics = ...)} requires a
 * compile-time constant expression, and a static method call doesn't qualify.
 */
public final class TriageTopics {

    public static final String CLASSIFY = "hivemind.triage.classify";
    public static final String CLASSIFIED = "hivemind.triage.classified";

    private TriageTopics() {
    }
}
