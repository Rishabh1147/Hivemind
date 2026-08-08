package com.hivemind.infra.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registering a {@link LoggingSpanExporter} bean is all Spring Boot needs to start writing every
 * finished span as a log line (trace id, span id, name, duration) — no collector/Jaeger/Zipkin
 * container required to see a trace end to end. Kept registered even now that a real backend exists
 * (see {@code management.otlp.tracing.endpoint} in {@code application.yml}, pointed at the Jaeger
 * container in {@code docker-compose.yml}): Spring Boot composes every {@code SpanExporter} bean it
 * finds into one composite exporter, so this and the OTLP exporter both receive every span — this
 * one keeps working with zero extra infra (`./mvnw test`, a local run without `docker compose up`),
 * the other gives an actual trace-search UI when the full stack is up.
 */
@Configuration
public class TracingConfig {

    @Bean
    public LoggingSpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
