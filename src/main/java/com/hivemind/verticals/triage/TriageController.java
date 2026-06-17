package com.hivemind.verticals.triage;

import com.hivemind.verticals.triage.model.Ticket;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/triage")
public class TriageController {

    @PostMapping("/tickets")
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody Ticket ticket) {
        String ticketId = UUID.randomUUID().toString();
        return ResponseEntity.accepted().body(Map.of(
                "id", ticketId,
                "status", "received"
        ));
    }
}
