package com.hivemind.verticals.triage;

import com.hivemind.platform.agent.AgentContext;
import com.hivemind.platform.agent.AgentResult;
import com.hivemind.verticals.triage.agents.ClassifierAgent;
import com.hivemind.verticals.triage.model.Classification;
import com.hivemind.verticals.triage.model.Ticket;
import com.hivemind.verticals.triage.model.TriageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/triage")
public class TriageController {

    private final ClassifierAgent classifierAgent;

    public TriageController(ClassifierAgent classifierAgent) {
        this.classifierAgent = classifierAgent;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TriageResponse> ingest(@Valid @RequestBody Ticket ticket) {
        String ticketId = UUID.randomUUID().toString();

        AgentContext context = new AgentContext(ticketId);
        context.put(ClassifierAgent.TICKET_BODY_KEY, ticket.body());

        AgentResult<Classification> result = classifierAgent.handle(context);

        if (!result.success()) {
            return ResponseEntity.unprocessableEntity()
                    .body(TriageResponse.failed(ticketId, result.errorMessage()));
        }
        return ResponseEntity.ok(TriageResponse.classified(ticketId, result.payload()));
    }
}
