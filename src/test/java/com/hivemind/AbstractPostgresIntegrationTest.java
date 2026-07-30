package com.hivemind;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every {@code @SpringBootTest} in this project now needs a real Postgres, not just a real Kafka
 * broker — Flyway runs migrations eagerly at context startup (unlike Spring Kafka's lazy-connect
 * beans), so a missing database fails the whole context, not just the code path that uses it.
 * Extracted here once a third test class needed the identical container + property wiring — the
 * same "wait for a real repeated need" discipline used everywhere else in this codebase, just
 * applied to test infrastructure instead of production code.
 */
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
