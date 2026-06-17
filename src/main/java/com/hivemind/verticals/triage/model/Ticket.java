package com.hivemind.verticals.triage.model;

import jakarta.validation.constraints.NotBlank;

public record Ticket(@NotBlank String body) {
}
