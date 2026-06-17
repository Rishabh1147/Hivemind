# Architecture

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

## Why Kafka between agents (vs in-process)

- **Replay**: re-run a request from any step using only the event log.
- **Backpressure**: slow tools don't block the planner.
- **Multi-tenancy**: shard by tenant ID via Kafka partition.
- **Observability**: the event log *is* the audit log.
- **Vertical isolation**: each vertical owns its own topic namespace
  (`hivemind.<vertical>.*`) — adding a new vertical is purely additive.

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

## Memory

- **Short-term** (Redis): last N turns of conversation, TTL 1h.
- **Long-term** (pgvector): semantic memory of resolved tickets, indexed
  for retrieval.
- **Audit log** (Postgres): every event, every tool call, every LLM
  request/response — immutable, append-only.

## Observability

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

## Failure modes & handling

| Failure | Handling |
|---|---|
| LLM rate limit | Exponential backoff; circuit breaker; fallback model |
| Tool timeout | Retry up to 3x; mark step failed; planner decides retry vs escalate |
| Kafka unavailable | Buffer in local outbox; replay on reconnect |
| Eval regression | CI blocks merge |
| Vector store unavailable | Fall back to BM25-only retrieval; flag in trace |

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

See [EXTENDING.md](EXTENDING.md) for how to add a new vertical.
