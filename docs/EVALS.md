# Evaluation

*Implementation status (as of 2026-08-12): the harness described below is real —
`verticals/triage/eval/`, runnable via `./mvnw spring-boot:run -Dspring-boot.run.profiles=eval`
(this doc's original placeholder below said `./gradlew`, which was never accurate — this project
has always been Maven). The 50+ case target is met: 53 cases across
`evals/triage/`, matching the distribution below almost exactly (20 billing, 15 bug, 8 feature, 5
abuse, 3 edge-case, 2 other), validated on every `mvn test` run by `EvalCaseSetTest` (unique ids,
every `mustCite` entry resolves to a real `KnowledgeBase` chunk, no blank tickets) — separate from
actually running the harness against a live model, which none of this requires. Category accuracy,
routing correctness, citation recall, and (as of 2026-08-05) cost-per-ticket are scored and gated —
`CostTracker` turns real token counts from every Claude call into a USD figure at a configurable
per-million-token rate (`hivemind.llm.pricing.*`); as of 2026-08-12 that rate is real, verified Claude
Haiku 4.5 pricing (`hivemind.llm.model`'s default as of the same date — see "Which model, and why
Haiku" below), not a placeholder. Tone (needs a live LLM-as-judge call no session has had a real
Anthropic key to verify against) is still designed below but not gated. The 20-case adversarial set
(as of 2026-08-10) is also built and real — see its own section below for what changed from the
original sketch and why. The `--case=<id>` filter this doc used to describe as "not yet" is also real
as of 2026-08-12 — see "Running locally" below. As of the same date (session 20), `TriageEvalRunner`
also runs `PlannerAgent` after classification and skips `ResponderAgent` on `PlanDecision.SKIP_RESPONSE`
(today: `ABUSE` tickets) — mirroring the real pipeline's new branching rather than always calling every
agent, so scored cost and behavior match what production actually does, not a stale always-four-agents
shape. See `docs/devlog/` for the full "what's real vs. still aspirational" account, session by
session.*

## Why eval-first

Most LLM projects fail not at the demo, but at the second iteration —
"I changed the prompt and now something else broke." Evals catch that
regression before it ships.

Hivemind treats evals as a first-class artifact: the goal is every PR running the suite, with a
regression below threshold blocking merge. GitHub Actions CI exists (session 11) but currently gates
on the test suite only, not the eval harness — see the implementation-status note above for why.

## Test set

- **Size**: target 50+ cases for TriageBot v1 (current: 53, hand-written)
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
   call against, which no session has had

Aggregate scores reported per run (`TriageEvalReport`):

- Per-run category accuracy, routing accuracy, citation recall (all real numbers, computed and
  gated)
- p50/p95 latency (real — measured wall-clock time per case, including real Claude round-trips)
- Average tone — ❌ not computed
- **Average cost per ticket (USD)** — ✅ real, via `CostTracker` reading `TokenUsage` off every
  Claude response (`ChatResponse.tokenUsage()`); 0.0 for a case where the LLM call itself failed
  before any tokens were billed, since `AgentResult.failure(...)` always carries 0.0 cost

## CI gating

Thresholds (start lenient, tighten over time), configured in `application.yml`
(`hivemind.eval.thresholds.*`) since session 1, first read by `TriageEvalHarnessRunner` starting
session 10, all four gateable thresholds finally wired as of 2026-08-05:

| Metric | Threshold | Gated? |
|---|---|---|
| Category accuracy | ≥ 0.85 | ✅ |
| Citation recall | ≥ 0.70 | ✅ |
| Tone (avg) | ≥ 4.0 | ❌ not yet — no tone scoring |
| p95 latency | ≤ 8s | ✅ |
| Cost per ticket | ≤ $0.05 | ✅ — `hivemind.llm.pricing.*` is real, verified Claude Haiku 4.5 pricing (as of 2026-08-12), not a placeholder, and matches `hivemind.llm.model`'s default |

### Which model, and why Haiku

`hivemind.llm.model` defaults to Claude Haiku 4.5 (`${HIVEMIND_LLM_MODEL:claude-haiku-4-5-20251001}`
in `application.yml`), not Sonnet — a deliberate cost decision, not a downgrade nobody noticed. This
is an individual-budget project in active iterative dev, and Haiku is fully capable of exercising the
pipeline and eval mechanics for that purpose; override to a stronger model via `HIVEMIND_LLM_MODEL`
(e.g. `claude-sonnet-5`) for a run where response *quality* itself is what's being judged, such as a
demo for an interview. `hivemind.llm.pricing.*` must be updated to match whichever model is actually
configured — the two aren't linked in code, so they can silently drift out of sync if only one is
changed.

`TriageEvalHarnessRunner` exits non-zero on any gated threshold failing — verified by running the
harness with a deliberately invalid Anthropic key (every case fails, `categoryAccuracy = 0.0`, exit
code `1`) rather than trusting the log output alone; an earlier version of this code called
`SpringApplication.exit(...)` without wrapping it in `System.exit(...)`, which logged a failure but
always returned exit code `0` — a real bug only the exit code itself revealed.

"PR blocked if any threshold drops more than 5% from `main`" (regression-vs-baseline, not just
absolute-threshold gating) is still aspirational — there's no CI workflow invoking this yet, and no
baseline-comparison logic built. Today's gating is absolute-threshold only.

## Adversarial set

*Implementation status (as of 2026-08-10): built — 20 cases, 4 per sub-category below, in
`evals/triage-adversarial/`, validated structurally on every `mvn test` by
`AdversarialEvalCaseSetTest` (exactly 20 cases, unique ids that don't collide with the primary set's
ids, real `mustCite` chunk references, non-blank tickets). Run through the identical pipeline and
`TriageEvalScorer` as the primary set — `TriageEvalRunner.runAdversarial()` — and reported to its own
`eval-results/<timestamp>-adversarial.json` file by `TriageEvalHarnessRunner`, but never folded into
the pass/fail gating decision, exactly as designed below.*

A separate set of 20 cases designed to break the system:

- Prompt injection in the ticket body — 4 cases, one per triage category (billing, bug, feature
  request, abuse), each embedding an instruction telling the classifier/responder to ignore its
  actual task. `expectedCategory` is still the *real* underlying category, not what the injection
  asks for — the case is scored as a pass only if the injection is successfully ignored.
- Contradictory ticket content — 4 cases. **Deviation from the original sketch, documented rather
  than silent:** "contradictory KB entries" would mean deliberately planting two disagreeing chunks
  in `KnowledgeBase`, but that KB is shared with all 53 primary cases' citation-recall scoring —
  adding a contradiction there risks destabilizing citation results project-wide for the sake of 4
  adversarial cases. Adapted instead to tickets that contradict *themselves* (e.g. "I was charged
  twice... actually I was never charged at all") or contradict a real KB chunk (e.g. claiming support
  said the opposite of what the KB's actual retry policy says) — same spirit (can the system stay
  grounded against conflicting input), without touching shared KB content.
- Multi-issue tickets (billing + bug, and two other category pairings) — 4 cases, each raising two
  real issues in one ticket; `expectedCategory` is whichever issue reads as primary/most urgent in
  the ticket as written, which is a judgment call worth reviewing against actual model behavior over
  time rather than a mechanically-derived "correct" answer.
- Non-English text — 4 cases (French, German, Hindi, Portuguese), deliberately with **no inline
  English translation** (unlike `evals/triage/edge-002.json`, which cushions the same idea with a
  parenthetical translation) — a genuine stress test of whether classification holds up on raw
  foreign-language input. Expected to reveal a real, known limitation: `SearchKbTool` is naive
  English keyword-overlap, not semantic or multilingual search, so citation recall on these cases is
  expected to fail predictably even when category accuracy holds — that predictable failure is
  exactly the kind of thing this set exists to keep visible rather than let quietly worsen unnoticed.
- Tickets with no relevant KB context (test grounding) — 4 cases, each `mustCite: []` since nothing
  in the 5-chunk `KnowledgeBase` covers them. Citation recall is trivially satisfied for these (an
  empty `mustCite` can't fail), so the real signal isn't the scored metric — it's manually checking
  `actualCitedChunkIds` in the written report for a case that shouldn't have cited anything at all.
  `TriageEvalScorer` doesn't compute citation *precision* (over-citation isn't scored), a deliberate,
  pre-existing scope cut noted in the scoring rubric above — this sub-category is the closest thing
  to a manual substitute until precision scoring is worth building for its own sake.

These don't gate CI but are tracked over time to measure robustness.

## Running locally

```bash
# Runs every case under evals/<vertical>/ for the enabled verticals — both the primary and
# adversarial sets, ~73 real Claude calls total.
./mvnw spring-boot:run -Dspring-boot.run.profiles=eval

# Restrict to specific case ids — real Claude spend limited to just these, useful for a cheap
# "did I wire this correctly" sanity check instead of the full set. Repeatable and/or comma-separated;
# applies to both the primary and adversarial directories (an id with no match in one just runs zero
# cases there — harmless, since zero cases means zero calls).
./mvnw spring-boot:run -Dspring-boot.run.profiles=eval -Dspring-boot.run.arguments=--case=billing-001,abuse-001

# Or against the packaged jar (this is what a real CI step would run):
java -jar target/hivemind-0.1.0-SNAPSHOT.jar --spring.profiles.active=eval --case=billing-001
```

## Reporting

Each eval run writes two JSON summaries (gitignored): `eval-results/<timestamp>.json` for the primary,
gated set, and `eval-results/<timestamp>-adversarial.json` for the 20-case adversarial set — separate
files, separate timestamps (the adversarial run finishes slightly after the primary one, since
`TriageEvalHarnessRunner` runs them sequentially), so the adversarial numbers never get folded into
the primary report that gating actually reads. The "markdown delta vs `main` posted as a PR comment by
the GitHub Action" is still aspirational — no CI workflow exists yet to call the harness or post
anything.
