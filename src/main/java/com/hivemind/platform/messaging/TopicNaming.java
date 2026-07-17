package com.hivemind.platform.messaging;

/**
 * Enforces the platform-wide topic naming convention ({@code hivemind.<vertical>.<stage>}) so
 * every vertical's topics are namespaced the same way. Verticals still declare their own topic
 * name constants (e.g. {@code TriageTopics}) as string literals rather than calling this
 * directly, because {@code @KafkaListener(topics = ...)} requires a compile-time constant and a
 * method call doesn't qualify — a test asserts those literals match this convention instead.
 */
public final class TopicNaming {

    private TopicNaming() {
    }

    public static String of(String vertical, String stage) {
        return "hivemind.%s.%s".formatted(vertical, stage);
    }
}
