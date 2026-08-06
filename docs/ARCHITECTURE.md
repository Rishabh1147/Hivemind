# Architecture

*This document predates the code — it's the original target design, written before any of it was
built. It's kept as-is deliberately (the destination is still useful to see), with an implementation
status note under each section marking what's real today vs. still ahead. For a narrative
walkthrough of what's actually running, see the README's "What's actually running today" section or
`docs/PROJECT_STRUCTURE.md` for a file-by-file built/planned breakdown.*

## Goals

1. **JVM-native** — no Python sidecars, single deployable per service.
2. **Observable** — every LLM call traced, every token costed.
3. **Evaluated** — no merge without passing the eval suite.
4. **Scalable** — KEDA-driven autoscaling on Kafka consumer lag.
5. **Replayable** — full conversation + tool-call audit trail in Postgres.
6. **Extensible** — new verticals plug in without core changes.

## Request lifecycle (TriageBot vertical)

1. **Ingest**: ticket arrives via REST → Spring Boot API Gateway.
2. **Plan**: Planner Agent receives the ticket, decides which sub-agents
   to invoke, publishes commands to Kafka topics
   (`hivemind.triage.classify`, etc.).
3. **Classify**: Classifier Agent consumes from `hivemind.triage.classify`,
   produces a category + confidence score, publishes to
   `hivemind.triage.classified`.
4. **Retrieve**: Retriever Agent runs hybrid search (BM25 + pgvector
   embeddings) over KB + past tickets, publishes top-K chunks to
   `hivemind.triage.retrieved`.
5. **Respond**: Responder Agent assembles context, calls Claude with the
   response-drafting tool, returns draft + citations.
6. **Route**: Planner Agent decides — auto-resolve, queue for human, or
   escalate based on confidence + category-policy.
7. **Stream**: Every step emits events to `hivemind.trace`; API Gateway
   consumes and pushes to Next.js dashboard via SSE.

*Implementation status: steps 1, 3, 4, 5 are real, with two differences from the sketch above.
There's no Planner Agent — step 2 is `TriageController` publishing directly to
`hivemind.triage.classify`, and step 6's routing is a deterministic `RoutingAgent` policy
(category/confidence → decision), not a planner decision; today's chain is four fixed Kafka hops,
not dynamically planned. Step 4's "hybrid search (BM25 + pgvector)" is naive keyword-overlap
scoring over 5 hardcoded `KnowledgeBase` chunks (`SearchKbTool`) — real retrieval, not yet the
described hybrid. Step 7 (SSE streaming to a dashboard) doesn't exist; there's no `hivemind.trace`
topic and no frontend. What does exist instead of step 7: distributed tracing (Micrometer +
OpenTelemetry) that follows one trace id through steps 1–6 via Kafka header propagation, exported as
log lines — a different mechanism answering a related but distinct need (debugging one request's
path, not a live human-facing dashboard).*

## Why Kafka between agents (vs in-process)

- **Replay**: re-run a request from any step using only the event log.
- **Backpressure**: slow tools don't block the planner.
- **Multi-tenancy**: shard by tenant ID via Kafka partition.
- **Observability**: the event log *is* the audit log.
- **Vertical isolation**: each vertical owns its own topic namespace
  (`hivemind.<vertical>.*`) — adding a new vertical is purely additive.

*Implementation status: replay, backpressure isolation, and vertical topic isolation are all real
and follow directly from the real Kafka wiring. "Observability: the event log is the audit log" is
literally true, not just a metaphor — `EventBus.publish()` writes to Kafka and appends an immutable
`audit_events` row in the same call. Multi-tenancy via partition-sharding is unbuilt — there's no
tenant concept in the code at all yet (this project has one implicit tenant).*

## Tool registry

Tools are Spring beans annotated with `@Tool(vertical=…, name=…)`. The
registry exposes:

- **Schema**: auto-generated JSON Schema from method signatures.
- **Timeout**: per-tool, defaults to 5s.
- **Retry**: exponential backoff with jitter, max 3 attempts.
- **Sandbox**: tools run on a separate virtual-thread executor with
  resource caps.

Initial TriageBot tools: `searchKB`, `searchPastTickets`,
`fetchUserHistory`, `escalateToHuman`, `sendResponse`.

*Implementation status: `ToolRegistry` (discovers `@Tool`-annotated beans at startup, catalogs by
name) and `ToolInvoker` (timeout, retry-on-timeout-only with full jitter, virtual-thread sandbox) are
both real. Of the five sketched tools, one is built — `searchKb` (naive keyword overlap over the
5-chunk `KnowledgeBase`), called by `RetrieverAgent` through the registry rather than injected
directly, deliberately the same lookup-by-name path a future planner-dispatched agent would use.
`searchPastTickets`, `fetchUserHistory`, `escalateToHuman`, `sendResponse` don't exist. Auto-generated
JSON Schema from method signatures isn't built either — there's no schema generation step; a tool is
called with whatever arguments the calling agent's code passes directly.*

## Memory

- **Short-term** (Redis): last N turns of conversation, TTL 1h.
- **Long-term** (pgvector): semantic memory of resolved tickets, indexed
  for retrieval.
- **Audit log** (Postgres): every event, every tool call, every LLM
  request/response — immutable, append-only.

*Implementation status: only the audit log is real, and narrower than sketched —
`AuditLog`/`audit_events` records every Kafka event `EventBus` publishes (vertical, entity id, event
type, JSON payload), not a separate per-tool-call or per-LLM-request/response log; a tool call or LLM
call's outcome is visible indirectly, through whichever event the agent that called it goes on to
publish. Short-term (Redis) and long-term (pgvector) memory are both unbuilt — there's no
conversation-turn memory or semantic ticket memory at all today; each ticket's pipeline run is
stateless beyond what's in the Kafka event and the `tickets` row itself.*

## Observability

*Implementation status (as of 2026-08-05): HTTP spans and Kafka producer/consumer spans are real —
Micrometer Tracing + the OpenTelemetry bridge, wired through `EventBus`/`EventConsumer` so trace
context propagates via Kafka headers across every stage. LLM spans (with the token/cost attributes
below) and tool spans are not instrumented as spans yet — though the cost half of that data is now
computed for real elsewhere: `CostTracker` turns each Claude call's `TokenUsage` into a USD figure,
used today to gate the eval harness's cost-per-ticket threshold, just not yet attached to a span as
`llm.cost_usd`. Exporting is a `LoggingSpanExporter` (spans as log lines) — real and verified, but not
Prometheus/Grafana; see the 2026-08-01 devlog for the "why the logging exporter for now" reasoning
and how propagation was verified against real infrastructure, and 2026-08-05 for `CostTracker`.*

OpenTelemetry instruments:

- HTTP spans (incoming + outgoing)
- Kafka producer/consumer spans
- LLM spans with attributes: `llm.model`, `llm.input_tokens`,
  `llm.output_tokens`, `llm.cost_usd`, `llm.latency_ms`, `llm.vertical`
- Tool spans with `tool.name`, `tool.success`, `tool.retry_count`,
  `tool.vertical`

Exported to Prometheus + Grafana. Per-ticket and per-vertical cost is
queryable.

## Scaling model

Each agent is a Spring Boot deployment with a KEDA `ScaledObject` keyed
on the consumer lag of its input Kafka topic. Targets:

- Lag > 100 → scale up
- Lag = 0 for 5 min → scale down to `minReplicas`

This validates the same pattern used in production at Contevolve.

*Implementation status: entirely unbuilt. There's no Kubernetes manifest, no KEDA `ScaledObject`, no
deployment automation at all — the app runs as a single process locally (`./mvnw spring-boot:run`) or
as a packaged jar. This section describes the target, not anything running today.*

## Failure modes & handling

| Failure | Handling | Status |
|---|---|---|
| LLM rate limit | Exponential backoff; circuit breaker; fallback model | ⚠️ partial — `LlmClient` retries `RetriableException` (rate limits, 5xx) with full-jitter backoff, up to 3 attempts by default. No circuit breaker, no fallback model |
| Tool timeout | Retry up to 3x; mark step failed; planner decides retry vs escalate | ⚠️ partial — `ToolInvoker` retries on timeout only (not on a tool's own thrown exception) with the same backoff. "Mark step failed" is real (`AgentResult.failure(...)`); there's no planner to decide retry-vs-escalate, since there's no planner |
| Kafka unavailable | Buffer in local outbox; replay on reconnect | ❌ not built — no local outbox; a publish failure surfaces as an exception |
| Eval regression | CI blocks merge | ⚠️ partial — the eval harness itself exits non-zero on a gating failure (real, verified); no CI workflow invokes it yet, so nothing currently blocks a merge on eval regression specifically (CI does block on the test suite) |
| Vector store unavailable | Fall back to BM25-only retrieval; flag in trace | ❌ not applicable yet — there's no vector store or BM25 to fall between; `SearchKbTool` is the only retrieval path today |

## Multi-vertical design

- **Topic naming**: `hivemind.<vertical>.<stage>` — strict namespacing
  prevents cross-vertical event leakage.
- **Agent registration**: every agent declares its vertical via
  `@AgentRole(vertical = "...", role = "...")`.
- **Tool registration**: every tool declares its vertical via
  `@Tool(vertical = "...", name = "...")`.
- **Eval cases**: organized by vertical (`evals/triage/`, `evals/codescout/`).
- **Shared infra is vertical-agnostic**: the planner runtime, tool
  registry, eval harness, observability pipeline, and Kafka bus do not
  know which vertical they're serving.

*Implementation status: the topic-naming convention, `@AgentRole`, `@Tool`, and the vertical-agnostic
`platform/` package are all real — enforced by discipline (`platform/` never imports from
`verticals/`) rather than a build-time check. `@AgentRole` is currently decorative metadata only, not
used for any dynamic dispatch (there's no planner reading it to route requests). Only one vertical
(`triage`) actually exists, so "vertical-agnostic shared infra" is a design property that's been
maintained, not yet proven by a second vertical genuinely exercising it — see `EXTENDING.md`'s own
status note.*

See [EXTENDING.md](EXTENDING.md) for how to add a new vertical.
