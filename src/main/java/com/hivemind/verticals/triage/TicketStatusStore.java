package com.hivemind.verticals.triage;

import com.hivemind.verticals.triage.model.TriageResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the Postgres-backed audit log (see {@code AuditLog} in
 * PROJECT_STRUCTURE.md's roadmap) until that lands — read side for ticket status now that
 * classification happens asynchronously off a Kafka consumer instead of in the request thread.
 */
@Component
public class TicketStatusStore {

    private final Map<String, TriageResponse> byId = new ConcurrentHashMap<>();

    public void put(TriageResponse response) {
        byId.put(response.id(), response);
    }

    public Optional<TriageResponse> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
