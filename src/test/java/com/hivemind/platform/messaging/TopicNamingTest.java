package com.hivemind.platform.messaging;

import com.hivemind.verticals.triage.messaging.TriageTopics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicNamingTest {

    @Test
    void triageTopicsFollowTheNamingConvention() {
        assertThat(TriageTopics.CLASSIFY).isEqualTo(TopicNaming.of("triage", "classify"));
        assertThat(TriageTopics.CLASSIFIED).isEqualTo(TopicNaming.of("triage", "classified"));
    }
}
