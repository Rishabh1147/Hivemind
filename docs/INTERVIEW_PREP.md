# Hivemind Interview Prep

Cumulative, organized by topic (not by day) — each session appends to the relevant section.
Angled at the "backend + AI agent infrastructure" niche, not generic Kafka/K8s 101 (see project
memory: this is deliberately the intersection of production distributed-systems experience and
LLM agent orchestration).

## Architecture decisions

**Q: Why LangChain4j instead of LangGraph?**
Two reasons. First, practical: LangGraph doesn't exist for the JVM — it's Python (and a JS port),
and Hivemind's whole premise is "no Python sidecar." LangChain4j is the JVM-native equivalent for
the LLM-*client* layer (chat calls, tool-calling schema, structured output) — it doesn't give you
LangGraph's graph/state-machine orchestration on top.
Second, deliberate: even if it existed for Java, Hivemind isn't using anything like it for
orchestration — Kafka is. LangGraph's state graph runs in-process in a single process: one node
calls the next, state passes in memory, and a crash mid-run loses your place. Hivemind swaps that
for a durable one — the planner publishes to Kafka topics, each agent is its own consumer and its
own independently-scaled deployment. That buys replay-from-any-step via the event log, per-agent
KEDA autoscaling on consumer lag, and the event log doubling as the audit trail — at the cost of
more moving parts and inter-step latency versus a direct function call.
So the split is: LangChain4j = "talk to Claude and call tools." A custom planner + Kafka event bus
= "what happens next." That's closer to how production agent systems at scale actually look than
a single-process graph library.

**Q: What's actually built vs. what's still just documented/planned?**
Be precise here — this is a portfolio project and overclaiming is the fastest way to lose
credibility in an interview. As of the last devlog: a single synchronous agent (`ClassifierAgent`)
calling Claude via LangChain4j, wired through a REST controller. No Kafka, no Postgres persistence,
no eval harness, no OpenTelemetry, no frontend yet. Check `docs/devlog/` for the current honest
state before claiming anything more specific.

## LLM / agent orchestration

**Q: Walk me through what happens when a ticket comes in, today.**
`POST /api/v1/triage/tickets` → `TriageController` builds an `AgentContext` (a request-scoped
attribute bag) → hands it to `ClassifierAgent.handle()` → the agent sends the ticket body to Claude
via `LlmClient` (a thin wrapper around LangChain4j's `ChatModel`) with a system prompt demanding
strict JSON output → parses the response into a `Category` enum + confidence score with Jackson →
wraps it in an `AgentResult` (success/payload or failure/error message) → the controller returns it
as JSON, or a 422 on failure.

**Q: Why wrap LangChain4j's `ChatModel` in your own `LlmClient` instead of injecting it directly
into agents?**
Every vertical talks to Claude through one indirection point. That's where cost tracking
(tokens → USD, per the `CostTracker` in the roadmap) and OpenTelemetry LLM spans get added later —
once, not per-agent. It also means swapping providers or adding retry/fallback logic never touches
agent code.

**Q: Why not use the raw Anthropic Java SDK instead of LangChain4j?**
LangChain4j gives structured chat message types (`SystemMessage`/`UserMessage`), a provider-agnostic
`ChatModel` interface, and (later) built-in tool-calling and structured-output helpers that would
otherwise be hand-rolled. Given the platform's stated goal of hosting *multiple* verticals with
possibly different tool-use patterns, that abstraction is worth the dependency.

**Q: How do you handle the LLM provider failing (auth error, rate limit, timeout)?**
Currently: `ClassifierAgent` wraps the LLM call in try/catch and returns `AgentResult.failure(...)`
rather than letting the exception propagate — found and fixed a real bug here (see the 2026-07-15
devlog) where that wrapping was initially missing and a real `AuthenticationException` surfaced as
an unhandled 500. Not yet built: distinguishing *retryable* upstream failures (429, 5xx) from
*non-retryable* ones (malformed model output) — that's designed to live in the `ToolInvoker`
(timeout/exponential-backoff retry/sandbox) from the roadmap, not bolted onto one agent ad hoc.

**Q: Why does the classifier ask for JSON via prompt instructions instead of using tool-calling /
structured outputs?**
Simplest thing that works for a single-field classification task today. Tool-calling schemas and
LangChain4j's structured-output support are the natural upgrade once there's more than one
extracted field or the JSON-parse-failure rate in practice justifies it — deliberately not adding
that abstraction before there's a concrete need for it.

## Kafka / eventing
*(not yet implemented — this section fills in once the event bus lands)*

## Evals
*(not yet implemented)*

## Observability
*(not yet implemented)*

## Scaling
*(not yet implemented)*
