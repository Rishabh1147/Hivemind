# Evaluation

## Why eval-first

Most LLM projects fail not at the demo, but at the second iteration —
"I changed the prompt and now something else broke." Evals catch that
regression before it ships.

Hivemind treats evals as a first-class artifact: every PR runs the suite,
and a regression below threshold blocks merge.

## Test set

- **Size**: target 50+ cases for TriageBot v1 (current: building)
- **Sources**: hand-written + synthesized + adversarial
- **Distribution** (TriageBot vertical):
  - 40% billing
  - 30% bug reports
  - 15% feature requests
  - 10% abuse/spam
  - 5% edge cases (multilingual, ambiguous, malicious)

Cases live in `evals/<vertical>/<id>.json` with the schema:

```json
{
  "id": "billing-001",
  "input": { "ticket": "..." },
  "expected": {
    "category": "billing",
    "routing": "auto",
    "must_cite": ["kb-1234"],
    "tone_min": 4
  }
}
```

## Scoring rubric

Each case has expected outputs for:

1. **Category** (exact match)
2. **Routing decision** (`auto` / `human` / `escalate`)
3. **Citations** (must include at least one of the gold-set KB entries)
4. **Tone** (LLM-as-judge: 1–5)

Aggregate scores reported per run:

- Per-category accuracy
- Citation precision/recall
- Average tone
- p50/p95 latency
- Average cost per ticket (USD)

## CI gating

Thresholds (start lenient, tighten over time):

| Metric | Threshold |
|---|---|
| Category accuracy | ≥ 0.85 |
| Citation recall | ≥ 0.70 |
| Tone (avg) | ≥ 4.0 |
| p95 latency | ≤ 8s |
| Cost per ticket | ≤ $0.05 |

PR blocked if any threshold drops more than 5% from `main`.

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
# (placeholder — will be filled in week 1)
./gradlew evalRun
./gradlew evalRun --case=billing-001
```

## Reporting

Each eval run writes a JSON summary to `eval-results/<timestamp>.json`
and a markdown delta vs `main` is posted as a PR comment by the GitHub
Action.
