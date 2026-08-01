package com.hivemind.infra.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registering a {@link LoggingSpanExporter} bean is all Spring Boot needs to start writing every
 * finished span as a log line (trace id, span id, name, duration) — no collector/Jaeger/Zipkin
 * container required to see a trace end to end. Good enough to verify propagation now; swapping in
 * an OTLP exporter later (a real backend) is a one-bean change, not a rewire.
 */
@Configuration
public class TracingConfig {

    @Bean
    public LoggingSpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
