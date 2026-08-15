# Hivemind

> A JVM-native multi-agent platform. Production-grade orchestration,
> evaluation, and observability for LLM agents — built in **Java 21 +
> Spring Boot**, designed to host multiple agent verticals on a shared
> runtime.

[![Build](https://github.com/Rishabh1147/Hivemind/actions/workflows/ci.yml/badge.svg)](https://github.com/Rishabh1147/Hivemind/actions)
[![Eval Score](https://img.shields.io/badge/eval--score-53%2F53%20passing-brightgreen)](docs/EVALS.md)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](#license)

---

## Verticals

| Vertical | Status | Description |
|---|---|---|
| **TriageBot** — support triage | v1, in progress | Classifies, routes, and drafts responses for inbound support tickets |
| **CodeScout** — code review | Planned (v2) | Reviews PRs for bugs, style, and security issues |
| **DeepDigger** — research | Planned (v3) | Multi-source web research with cited synthesis |

Verticals are meant to share one runtime — tool registry, eval harness, observability, and Kafka
event bus — so a new vertical means new agents + tools + eval cases, no core platform changes. That
promise is currently proven by discipline (`platform/` never imports from `verticals/`), not yet by
a second vertical actually existing; CodeScout/DeepDigger haven't started.

## Why this exists

Most LLM agent frameworks today are Python-first. That's fine for a notebook, but production systems
at most companies already run on the JVM — Spring Boot, Kafka, observability stacks, Kubernetes —
and bolting a Python sidecar onto that adds a second runtime, a second dependency ecosystem, and a
network hop that didn't need to exist.

Hivemind's bet: agent orchestration is a distributed-systems problem wearing an LLM costume, and the
JVM is already good at distributed systems. It's a solo portfolio project, built to demonstrate that
intersection — production-shaped infrastructure (Kafka, Spring, Postgres, CI, tracing) around a real
LLM pipeline, not just a script that calls an LLM.

## What's actually running today (TriageBot v1)

A ticket arrives via `POST /api/v1/triage/tickets` and is handed off through **four independent
agents, each triggered by a Kafka event, each publishing an event for the next stage**:

```
POST /tickets  ──▶  ClassifierAgent  ──▶  RetrieverAgent  ──▶  ResponderAgent  ──▶  RoutingAgent
 (202 Accepted,        (Claude call:           (searchKb tool:        (Claude call:           (no LLM —
  returns immediately)  category+confidence)    keyword-overlap        drafted answer          deterministic
                                                 over 5 KB chunks)      + citations)            policy)
```

The API is asynchronous by design — `POST` doesn't wait for classification, it publishes an event
and returns immediately; `GET /api/v1/triage/tickets/{id}` reads whatever stage the ticket has
reached from Postgres. A real `PlannerAgent` (session 20) sits between classify and respond and
decides one thing: whether the ticket gets a drafted response at all — an `ABUSE` classification
skips `ResponderAgent`'s Claude call entirely, since `RoutingAgent` escalates every `ABUSE` ticket
regardless of what got drafted, while retrieval still runs so the citation trail survives. It's a
first real branching decision, not a full dynamic planner: it doesn't decide retrieval, doesn't
reorder stages, and doesn't run at ingest — everything except `ABUSE` still takes all four hops. See
[`OVERVIEW.md`](OVERVIEW.md) (gitignored, personal) or [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
for the full walkthrough and the reasoning behind every design choice.

## Tech stack

| Layer | Choice | Status |
|---|---|---|
| Language | **Java 21** | ✅ records, virtual threads, text blocks in active use |
| Framework | **Spring Boot 3.3.5** | ✅ |
| LLM SDK | **LangChain4j 1.17.2** | ✅ client layer only — Kafka does orchestration, not LangChain4j |
| LLM | **Anthropic Claude** | ✅ Haiku 4.5 by default (individual-budget dev cost control), overridable to Sonnet via `HIVEMIND_LLM_MODEL`. Two providers: direct Anthropic API (default) or AWS Bedrock (`HIVEMIND_LLM_PROVIDER=bedrock`, session 21) — same `ChatModel` interface either way, so nothing downstream of `ClaudeConfig` knows or cares which one is active |
| Messaging | **Apache Kafka** (KRaft) | ✅ durable event bus between all four agents |
| Persistence | **PostgreSQL 16 + Flyway** | ✅ ticket state + immutable audit log (`JdbcTemplate`, not JPA) |
| Testing | **Testcontainers** | ✅ real Postgres + Kafka per integration test, no mocks |
| Observability | **Micrometer Tracing + OpenTelemetry** | ✅ one trace id across the HTTP request, every Kafka hop, every LLM call, and every tool call — exported as log lines *and* over OTLP to a real Jaeger backend (`docker-compose.yml`), queryable via Jaeger's own UI/API, not Prometheus/Grafana |
| Cost tracking | **`CostTracker`** | ✅ real per-call USD from token counts, tagged onto the LLM span as `llm.cost_usd`; pricing rates are real, verified Haiku 4.5 pricing, not placeholders |
| CI | **GitHub Actions** | ✅ full test suite on every push/PR — not yet gating on the eval harness itself (cost/secrets tradeoff) |
| Vector DB | pgvector | ❌ planned — knowledge base is 5 hardcoded chunks today |
| Cache | Redis | ❌ planned |
| Frontend | Next.js | ❌ planned — no dashboard, no SSE stream |
| Orchestration | Kubernetes + KEDA | ❌ planned |

## Eval-first development

An eval harness (`verticals/triage/eval/`) runs 53 hand-written cases — the 50+ target from
[`docs/EVALS.md`](docs/EVALS.md), matching its stated category distribution — through the real
pipeline and scores:

- **Category accuracy** — did the classifier pick the right category?
- **Routing correctness** — where the outcome doesn't depend on model confidence (e.g. abuse always
  escalates)
- **Citation recall** — does the response cite at least one real, relevant KB entry?
- **Tone** (LLM-as-judge, 1–5) — `TriageEvalToneJudge`, a real Claude call
- **p95 latency**
- **Cost per ticket** — real dollars, computed from actual token counts

All five are gated with a real process exit code
(`./mvnw spring-boot:run -Dspring-boot.run.profiles=eval`) — first run against a real, funded key was
2026-08-15 (session 21, via AWS Bedrock — direct Anthropic credits were unfunded that session): 53/53
primary cases passed every gate (100% category/routing/citation accuracy, avg tone 4.32/5, p95
5.2s, $0.00094/ticket), and the full 73-case run (primary + adversarial + every tone-judge call) cost
$0.09 total. The same run also scores a separate, 20-case **adversarial set**
(`evals/triage-adversarial/` — prompt injection, contradictory ticket content, multi-issue tickets,
non-English text, no-relevant-KB-context grounding checks) through the identical pipeline, written to
its own report file but never gated — it's designed to surface known weaknesses and get tracked over
time, not to pass 100% of the time; that first real run scored 90% category accuracy and did exactly
that: it caught a real, non-English-specific parsing gap under prompt injection (the model correctly
resisted the injection but broke the strict-JSON contract by appending defensive prose after the
answer) — see [`docs/EVALS.md`](docs/EVALS.md) for the full story. **This gate runs locally, not in
CI yet** — GitHub Actions currently runs the unit/integration test suite only; wiring the eval harness
into CI needs a funded key as a GitHub secret and real spend per push, a deliberate tradeoff not yet
made. See [`docs/EVALS.md`](docs/EVALS.md) for the full scoring rubric and reasoning.

## Target architecture (all verticals, v3)

The diagram below is the destination, not the current state — a *full* dynamic planner (one real
branching decision exists today, see above), a second and third vertical, pgvector/Redis, and a live
dashboard are all still ahead. See "What's actually running
today" above for what's real right now.

```
                    ┌──────────────────┐
                    │  Next.js UI      │  ◄── live SSE trace stream (planned)
                    └────────┬─────────┘
                             │ REST
                    ┌────────▼─────────┐
                    │  API Gateway     │
                    │  (Spring Boot)   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐         ┌──────────────┐
                    │  Planner Agent   │ ──────► │  Tool        │
                    │  (ingest-time*)  │ ◄────── │  Registry    │
                    └────────┬─────────┘         └──────────────┘
                             │ Kafka topics: hivemind.<vertical>.*
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼────┐         ┌─────▼─────┐        ┌─────▼─────┐
   │ Triage  │         │ CodeScout │        │ DeepDigger│
   │ vertical│         │ (planned) │        │ (planned) │
   │ agents  │         └───────────┘        └───────────┘
   └────┬────┘
        │
   ┌────▼─────────────────────────────────────────────┐
   │ Shared infra: Postgres (real) + pgvector (planned),
   │ Redis (planned), OpenTelemetry (HTTP/Kafka/LLM/tool spans
   │ real, exported to real Jaeger), eval harness (real)
   └──────────────────────────────────────────────────┘
```

\* Ingest-time dispatch (a planner deciding which agents to invoke before classify even runs) is
still planned. A real, narrower planner decision exists today, mid-pipeline: `PlannerAgent` skips
`ResponderAgent`'s Claude call for `ABUSE` tickets — see "What's actually running today" above.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design and section-by-section
implementation status, [`docs/EVALS.md`](docs/EVALS.md) for the evaluation methodology,
[`docs/EXTENDING.md`](docs/EXTENDING.md) for how a new vertical is meant to plug in (unverified — no
second vertical has been built yet), and [`docs/PROJECT_STRUCTURE.md`](docs/PROJECT_STRUCTURE.md)
for the package layout, file by file, built vs. planned.

## Roadmap

### v1 — TriageBot vertical
- [x] Spring Boot 3.3 / Java 21 scaffold + LangChain4j/Claude wired
- [x] Four-stage Kafka pipeline: Classifier → Retriever → Responder → Routing agents
- [x] Postgres persistence — ticket state + immutable audit log (the event log doubles as the audit log)
- [x] Tool registry + first real tool (`searchKb`), timeout/retry/virtual-thread sandboxing
- [x] Eval harness — 53 cases (50+ target met), gated on category accuracy / citation recall / p95 latency / cost-per-ticket
- [x] Adversarial eval set — 20 cases (injection, contradiction, multi-issue, non-English, grounding), tracked but not gated
- [x] GitHub Actions CI — full test suite on every push/PR
- [x] Distributed tracing — one trace id across the HTTP request, every Kafka hop, every LLM call
      (`llm.model`/`llm.cost_usd`/token-count tags), and every tool call (`tool.name`/`tool.success`/
      `tool.retry_count` tags), exported to a real Jaeger backend over OTLP (plus log lines)
- [x] A first real planner decision — `PlannerAgent` skips response-drafting for `ABUSE` tickets
      (no wasted Claude call), verified over real Kafka; not yet a *full* dynamic planner (see below)
- [x] AWS Bedrock as a second LLM provider (`HIVEMIND_LLM_PROVIDER=bedrock`) — useful when direct
      Anthropic credits are unfunded but AWS credits aren't; same `ChatModel` seam, no agent-level change
- [x] Tone scoring (LLM-as-judge) — all five eval thresholds now gated; first real run 2026-08-15
- [ ] CI gating on the eval harness itself (currently tests only)
- [ ] A full dynamic planner — today's one real decision doesn't cover retrieval, reordering, or
      ingest-time dispatch; most tickets still take all four fixed Kafka hops
- [ ] pgvector-backed KB persistence, Redis short-term memory
- [ ] Next.js dashboard with a live SSE trace stream

### v2 — CodeScout vertical (planned, after v1 ships)
- [ ] PR review agent: bug detection, style, security
- [ ] GitHub webhook integration
- [ ] Per-language reviewers (Java, TS, Python)

### v3 — DeepDigger vertical (planned, after v2)
- [ ] Multi-source web research with citations
- [ ] Adversarial verification of claims
- [ ] Synthesizer agent producing structured reports

> Hivemind's MVP is the TriageBot vertical. v2 and v3 demonstrate platform extensibility but won't be
> built until v1 is genuinely done — see the roadmap above for exactly what's left.

## Quick start

Requires Docker (for Kafka + Postgres + Jaeger) and a JDK 21. A funded key is only needed for real
classify/respond calls to succeed — the app boots and the test suite passes without one.

```bash
docker compose up -d          # Kafka (KRaft) + Postgres + Jaeger
./mvnw test                   # 64 tests — Testcontainers spins up its own Kafka/Postgres, no external services needed
ANTHROPIC_API_KEY=sk-... ./mvnw spring-boot:run

# Or via AWS Bedrock instead of direct Anthropic — same app, same agents, different provider:
# AWS_BEARER_TOKEN_BEDROCK=... HIVEMIND_LLM_PROVIDER=bedrock \
#   HIVEMIND_LLM_MODEL=us.anthropic.claude-haiku-4-5-20251001-v1:0 ./mvnw spring-boot:run

curl -X POST localhost:8080/api/v1/triage/tickets \
  -H "Content-Type: application/json" \
  -d '{"body": "I was charged twice for my subscription this month"}'
# → 202 Accepted, {"id": "...", "status": "pending", ...}

curl localhost:8080/api/v1/triage/tickets/<id>   # poll for classified → responded → routed
```

Open `http://localhost:16686` for the Jaeger UI and search for service `hivemind` to see the trace
from that request — one trace id spanning the HTTP call, every Kafka hop, and the LLM call.

Run the eval harness locally (also needs a funded key to produce a meaningful score, not just a
correctly-failing one — defaults to Haiku 4.5 to keep that cheap; the full 73-case run, both sets,
every tone-judge call included, cost $0.09 for real against Bedrock on 2026-08-15):

```bash
ANTHROPIC_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval

# Or restrict to specific cases for a cheap sanity check instead of the full set:
ANTHROPIC_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval -Dspring-boot.run.arguments=--case=billing-001
```

## Status

Active development, started June 2026. **Not production-ready** — this is a portfolio-grade
reference implementation; treat the `main` branch as work-in-progress. See the roadmap above for
exactly what's built vs. still ahead, and `docs/devlog/` for the session-by-session history.

## License

MIT
