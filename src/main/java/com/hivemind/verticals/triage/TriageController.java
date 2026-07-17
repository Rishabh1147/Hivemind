package com.hivemind.verticals.triage;

import com.hivemind.platform.messaging.EventBus;
import com.hivemind.verticals.triage.events.ClassifyRequested;
import com.hivemind.verticals.triage.messaging.TriageTopics;
import com.hivemind.verticals.triage.model.Ticket;
import com.hivemind.verticals.triage.model.TriageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/triage")
public class TriageController {

    private final EventBus eventBus;
    private final TicketStatusStore ticketStatusStore;

    public TriageController(EventBus eventBus, TicketStatusStore ticketStatusStore) {
        this.eventBus = eventBus;
        this.ticketStatusStore = ticketStatusStore;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TriageResponse> ingest(@Valid @RequestBody Ticket ticket) {
        String ticketId = UUID.randomUUID().toString();

        TriageResponse pending = TriageResponse.pending(ticketId);
        ticketStatusStore.put(pending);
        eventBus.publish(TriageTopics.CLASSIFY, ticketId, new ClassifyRequested(ticketId, ticket.body()));

        return ResponseEntity.accepted().body(pending);
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TriageResponse> status(@PathVariable String id) {
        return ticketStatusStore.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
