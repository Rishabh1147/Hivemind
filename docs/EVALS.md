# Evaluation

*Implementation status (as of 2026-07-30, session 10): the harness described below is real —
`verticals/triage/eval/`, runnable via `./mvnw spring-boot:run -Dspring-boot.run.profiles=eval`
(this doc's original placeholder below said `./gradlew`, which was never accurate — this project
has always been Maven). 10 of the 50+ target cases exist. Category accuracy, routing correctness,
and citation recall are scored and gated; tone (needs a live LLM-as-judge call) and cost-per-ticket
(needs the not-yet-built `CostTracker`) are designed below but not yet gated. See the
2026-07-30-session10 devlog for the full "what's real vs. still aspirational" account and the
reasoning behind each deviation from this original design.*

## Why eval-first

Most LLM projects fail not at the demo, but at the second iteration —
"I changed the prompt and now something else broke." Evals catch that
regression before it ships.

Hivemind treats evals as a first-class artifact: every PR runs the suite,
and a regression below threshold blocks merge.

## Test set

- **Size**: target 50+ cases for TriageBot v1 (current: 10, hand-written)
- **Sources**: hand-written + synthesized + adversarial
- **Distribution** (TriageBot vertical):
  - 40% billing
  - 30% bug reports
  - 15% feature requests
  - 10% abuse/spam
  - 5% edge cases (multilingual, ambiguous, malicious)

Cases live in `evals/<vertical>/<id>.json`. The actual schema (`TriageEvalCase`) is flatter than
originally sketched here, and `routing` is nullable — both explained below:

```json
{
  "id": "billing-001",
  "ticket": "A customer reports being charged twice in the same billing cycle — please check the transaction log for a duplicate charge and issue a refund.",
  "expectedCategory": "BILLING",
  "expectedRouting": null,
  "mustCite": ["billing-duplicate-charge"]
}
```

Two deliberate deviations from the original sketch above:

- **camelCase, not `snake_case`, and no nested `input`/`expected` objects.** This doc predates the
  code; matching Java field-naming convention directly (rather than configuring a non-default
  Jackson naming strategy just for eval cases) was a small, conscious implementation choice, not an
  oversight.
- **`routing` is nullable and usually null.** Routing depends on model confidence, which isn't
  knowable when a human authors a gold-labeled case — it's an output of running the classifier, not
  an input the case controls. Only cases where the outcome doesn't depend on confidence (e.g. an
  `ABUSE` ticket, which always escalates) assert it; a null value means "not applicable to this
  case," not "expected to be null," and isn't counted as a failure either way.

## Scoring rubric

Each case has expected outputs for:

1. **Category** (exact match) — ✅ scored, `TriageEvalScorer`
2. **Routing decision** (`AUTO_RESOLVE` / `QUEUE_FOR_HUMAN` / `ESCALATE`) — ✅ scored where
   `expectedRouting` is non-null
3. **Citations** (must include at least one of the gold-set KB entries) — ✅ scored (recall only;
   precision isn't computed — with a 5-chunk `KnowledgeBase` and top-K retrieval, over-citation
   isn't a failure mode worth a separate metric yet)
4. **Tone** (LLM-as-judge: 1–5) — ❌ not scored yet; needs a live Anthropic key to verify the judge
   call against, which this session didn't have

Aggregate scores reported per run (`TriageEvalReport`):

- Per-run category accuracy, routing accuracy, citation recall (all real numbers, computed and
  gated)
- p50/p95 latency (real — measured wall-clock time per case, including real Claude round-trips)
- Average tone — ❌ not computed
- Average cost per ticket (USD) — ❌ not computed, needs `CostTracker` (roadmap, not built)

## CI gating

Thresholds (start lenient, tighten over time), configured in `application.yml`
(`hivemind.eval.thresholds.*`) since session 1 and finally read by
`TriageEvalHarnessRunner` as of session 10:

| Metric | Threshold | Gated? |
|---|---|---|
| Category accuracy | ≥ 0.85 | ✅ |
| Citation recall | ≥ 0.70 | ✅ |
| Tone (avg) | ≥ 4.0 | ❌ not yet — no tone scoring |
| p95 latency | ≤ 8s | ✅ |
| Cost per ticket | ≤ $0.05 | ❌ not yet — no `CostTracker` |

`TriageEvalHarnessRunner` exits non-zero on any gated threshold failing — verified by running the
harness with a deliberately invalid Anthropic key (every case fails, `categoryAccuracy = 0.0`, exit
code `1`) rather than trusting the log output alone; an earlier version of this code called
`SpringApplication.exit(...)` without wrapping it in `System.exit(...)`, which logged a failure but
always returned exit code `0` — a real bug only the exit code itself revealed.

"PR blocked if any threshold drops more than 5% from `main`" (regression-vs-baseline, not just
absolute-threshold gating) is still aspirational — there's no CI workflow invoking this yet, and no
baseline-comparison logic built. Today's gating is absolute-threshold only.

## Adversarial set

A separate set of 20 cases designed to break the system:

- Prompt injection in the ticket body
- Contradictory KB entries
- Multi-issue tickets (billing + bug)
- Non-English text
- Tickets with no relevant KB context (test grounding)

These don't gate CI but are tracked over time to measure robustness.

## Running locally

```bash
# Runs every case under evals/<vertical>/ for the enabled verticals.
./mvnw spring-boot:run -Dspring-boot.run.profiles=eval

# Or against the packaged jar (this is what a real CI step would run):
java -jar target/hivemind-0.1.0-SNAPSHOT.jar --spring.profiles.active=eval
```

There's no per-case filter (`--case=billing-001`) yet — the harness always runs the full case set
for the vertical.

## Reporting

Each eval run writes a JSON summary to `eval-results/<timestamp>.json` (gitignored). The
"markdown delta vs `main` posted as a PR comment by the GitHub Action" is still aspirational — no
CI workflow exists yet to call the harness or post anything.
