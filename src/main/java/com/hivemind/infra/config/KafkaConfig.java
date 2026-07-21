package com.hivemind.infra.config;

import com.hivemind.verticals.triage.messaging.TriageTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly declares topics (partitions, replication) as beans rather than relying on broker
 * auto-create, which production clusters typically disable. {@code infra/} is allowed to know
 * about specific verticals' topic names — it's wiring, not domain logic (see PROJECT_STRUCTURE.md).
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic triageClassifyTopic() {
        return TopicBuilder.name(TriageTopics.CLASSIFY).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic triageClassifiedTopic() {
        return TopicBuilder.name(TriageTopics.CLASSIFIED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic triageRetrievedTopic() {
        return TopicBuilder.name(TriageTopics.RETRIEVED).partitions(3).replicas(1).build();
    }
}
