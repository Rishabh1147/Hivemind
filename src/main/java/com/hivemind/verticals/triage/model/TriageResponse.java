package com.hivemind.verticals.triage.model;

public record TriageResponse(String id, String status, Category category, Double confidence, String error) {

    public static TriageResponse classified(String id, Classification classification) {
        return new TriageResponse(id, "classified", classification.category(), classification.confidence(), null);
    }

    public static TriageResponse failed(String id, String error) {
        return new TriageResponse(id, "classification_failed", null, null, error);
    }
}
