# Hivemind

> A JVM-native multi-agent platform. Production-grade orchestration,
> evaluation, and observability for LLM agents — built in **Java 21 +
> Spring Boot**, designed to host multiple agent verticals on a shared
> runtime.

[![Build](https://github.com/Rishabh1147/Hivemind/actions/workflows/ci.yml/badge.svg)](https://github.com/Rishabh1147/Hivemind/actions)
[![Eval Score](https://img.shields.io/badge/eval--score-pending-lightgrey)](#)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](#license)

---

## Verticals

| Vertical | Status | Description |
|---|---|---|
| **TriageBot** — support triage | In development (v1 target: Jul 2026) | Classifies, routes, and drafts responses for inbound support tickets |
| **CodeScout** — code review | Planned (v2) | Reviews PRs for bugs, style, and security issues |
| **DeepDigger** — research | Planned (v3) | Multi-source web research with cited synthesis |

Verticals share the same runtime: planner agent, tool registry, eval
harness, observability, and Kafka event bus. A new vertical = a new set
of agents + tools + eval cases. **No core changes required.**

## Why this exists

95% of LLM agent platforms today are written in Python. That's fine for
research, but production systems run on the JVM — Spring Boot, Kafka,
observability stacks, K8s autoscaling — and forcing a Python sidecar into
that world adds latency, deployment complexity, and a polyglot ops burden.

Most LLM agents are also one-off scripts. The hard problems —
evaluation, observability, replay, autoscaling, cost tracking — are the
same across verticals. **Hivemind solves them once.** Each new vertical
inherits production-grade infrastructure for free.

## Architecture

```
                    ┌──────────────────┐
                    │  Next.js 15 UI   │  ◄── live SSE trace stream
                    └────────┬─────────┘
                             │ REST
                    ┌────────▼─────────┐
                    │  API Gateway     │
                    │  (Spring Boot)   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐         ┌──────────────┐
                    │  Planner Agent   │ ──────► │  Tool        │
                    │  (LangChain4j)   │ ◄────── │  Registry    │
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
   │ Shared infra: Postgres + pgvector, Redis,        │
   │ OpenTelemetry, Eval harness, KEDA autoscaling    │
   └──────────────────────────────────────────────────┘
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design,
[`docs/EVALS.md`](docs/EVALS.md) for the evaluation methodology,
[`docs/EXTENDING.md`](docs/EXTENDING.md) for how new verticals plug in,
and [`docs/PROJECT_STRUCTURE.md`](docs/PROJECT_STRUCTURE.md) for the
package layout.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | **Java 21** | Virtual threads, records, pattern matching |
| Framework | **Spring Boot 3.3** | Production defaults, ecosystem |
| LLM SDK | **LangChain4j** | JVM-native: chat, tools, structured output |
| LLM | **Anthropic Claude** | Strong tool use + reasoning |
| Messaging | **Apache Kafka** | Durable event bus between agents |
| Vector DB | **pgvector** | One DB instead of two; hybrid search |
| Cache | **Redis** | Short-term memory, rate limiting |
| Frontend | **Next.js 15** (App Router) | SSE streaming, modern React |
| Observability | **OpenTelemetry + Prometheus + Grafana** | Trace every LLM call, token cost |
| Orchestration | **Kubernetes + KEDA** | Autoscale on Kafka lag |
| CI | **GitHub Actions** | Eval suite blocks regressions |

## Current focus: TriageBot vertical

A customer support ticket arrives. TriageBot:

1. **Classifies** the issue (billing / bug / feature-request / abuse / …)
2. **Retrieves** relevant context from the knowledge base + past tickets
3. **Drafts** a response, citing sources
4. **Routes** the ticket: auto-resolve, queue for human, or escalate
5. **Streams** every step to a live dashboard for human review and override

Every step is a separate agent communicating over a Kafka event bus,
with full audit trail in Postgres and OpenTelemetry traces for every
LLM call.

## Eval-first development

Every PR runs an automated evaluation harness against a fixed test set
(target 50+ cases) scoring:

- **Correctness** — did the classifier pick the right category?
- **Groundedness** — does the response cite real KB entries?
- **Tool-use accuracy** — were the right tools called in the right order?
- **Latency** — p50/p95/p99 end-to-end.
- **Cost** — tokens per ticket.

If any score drops below threshold, the PR is blocked. See
[`docs/EVALS.md`](docs/EVALS.md).

## Roadmap

### v1 — TriageBot vertical (June–July 2026)
- [ ] Spring Boot scaffold + LangChain4j wired
- [ ] Classifier + Retriever + Responder agents
- [ ] Kafka event bus, OpenTelemetry traces
- [ ] Eval harness with 50+ cases, CI-gated
- [ ] Next.js dashboard, K8s + KEDA deploy

### v2 — CodeScout vertical (planned, after v1 ships)
- [ ] PR review agent: bug detection, style, security
- [ ] GitHub webhook integration
- [ ] Per-language reviewers (Java, TS, Python)

### v3 — DeepDigger vertical (planned, after v2)
- [ ] Multi-source web research with citations
- [ ] Adversarial verification of claims
- [ ] Synthesizer agent producing structured reports

> Hivemind's MVP is the TriageBot vertical. v2 and v3 demonstrate
> platform extensibility but won't be built until v1 is production-ready
> and the architecture is validated.

## Quick start

```bash
# (placeholder — will be filled in week 1)
docker compose up
./gradlew bootRun
```

## Status

Active development. Started June 2026. **Not production-ready yet** —
this is a portfolio-grade reference implementation; treat the `main`
branch as work-in-progress.

## License

MIT
