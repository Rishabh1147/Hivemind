package com.hivemind.verticals.triage.kb;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hardcoded in-memory KB chunks standing in for a real store (Postgres, per the roadmap) until
 * there's a Responder step that actually needs persisted/editable KB content.
 */
@Component
public class KnowledgeBase {

    private final List<KbChunk> chunks = List.of(
            new KbChunk(
                    "billing-duplicate-charge",
                    "Duplicate charges",
                    "If a customer reports being charged twice for the same billing cycle, check the "
                            + "payment provider's transaction log for a duplicate charge ID before issuing a refund."),
            new KbChunk(
                    "billing-failed-payment",
                    "Failed payment retries",
                    "Failed payments are retried automatically up to 3 times over 5 days before the "
                            + "subscription is marked past due."),
            new KbChunk(
                    "bug-report-triage",
                    "Bug report triage",
                    "Bug reports should include reproduction steps, expected vs actual behavior, and "
                            + "environment details before being routed to engineering."),
            new KbChunk(
                    "feature-request-process",
                    "Feature request handling",
                    "Feature requests are logged and reviewed monthly by product; customers are notified "
                            + "when a request is scheduled or declined."),
            new KbChunk(
                    "abuse-policy",
                    "Abuse and spam policy",
                    "Accounts sending unsolicited bulk messages or attempting credential stuffing are "
                            + "suspended immediately pending review."));

    public List<KbChunk> all() {
        return chunks;
    }
}
