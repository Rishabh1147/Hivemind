package com.hivemind.infra.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.verticals.triage.model.Category;
import com.hivemind.verticals.triage.model.RoutingDecision;
import com.hivemind.verticals.triage.model.TriageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed replacement for the in-memory {@code TicketStatusStore} — the current-state read
 * model behind {@code GET /api/v1/triage/tickets/{id}}, upserted in place as a ticket moves through
 * the pipeline. Plain {@link JdbcTemplate}, not Spring Data JPA: the schema is one row per ticket
 * with no relationships to speak of, so entity lifecycle/lazy-loading machinery would be ceremony
 * without payoff — the same "thin explicit wrapper" shape as {@code EventBus} and {@code LlmClient}.
 */
@Component
public class TicketRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void put(TriageResponse response) {
        jdbcTemplate.update(
                """
                INSERT INTO tickets (id, status, category, confidence, answer, cited_chunk_ids, routing_decision, error, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    category = EXCLUDED.category,
                    confidence = EXCLUDED.confidence,
                    answer = EXCLUDED.answer,
                    cited_chunk_ids = EXCLUDED.cited_chunk_ids,
                    routing_decision = EXCLUDED.routing_decision,
                    error = EXCLUDED.error,
                    updated_at = now()
                """,
                response.id(),
                response.status(),
                response.category() != null ? response.category().name() : null,
                response.confidence(),
                response.answer(),
                toJson(response.citedChunkIds()),
                response.routingDecision() != null ? response.routingDecision().name() : null,
                response.error());
    }

    public Optional<TriageResponse> get(String id) {
        return jdbcTemplate.query("SELECT * FROM tickets WHERE id = ?", this::mapRow, id).stream().findFirst();
    }

    private TriageResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        String category = rs.getString("category");
        String routingDecision = rs.getString("routing_decision");
        Double confidence = rs.getObject("confidence") != null ? rs.getDouble("confidence") : null;
        return new TriageResponse(
                rs.getString("id"),
                rs.getString("status"),
                category != null ? Category.valueOf(category) : null,
                confidence,
                rs.getString("answer"),
                fromJson(rs.getString("cited_chunk_ids")),
                routingDecision != null ? RoutingDecision.valueOf(routingDecision) : null,
                rs.getString("error"));
    }

    private String toJson(List<String> citedChunkIds) {
        try {
            return objectMapper.writeValueAsString(citedChunkIds);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize citedChunkIds", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cited_chunk_ids column: " + json, e);
        }
    }
}
