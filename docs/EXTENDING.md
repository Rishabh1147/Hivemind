# Extending Hivemind: adding a new vertical

*Implementation status: this is the intended process, written before any vertical beyond `triage`
existed — it has never actually been exercised. `platform/` genuinely doesn't import from
`verticals/` today, which is the load-bearing property this whole guide depends on, but "add
CodeScout" hasn't been tried yet, so treat the steps below as a credible plan, not a verified one.
Several of the "what you get for free" items further down (planner-driven dispatch, tool schema
generation, the Redis/pgvector memory layer, KEDA autoscaling) don't exist yet either — see each
bullet's note.*

A vertical = (1) a set of agents, (2) their tools, (3) eval cases,
(4) optional vertical-specific schemas. **Core platform code does not
change** — the planner runtime, tool registry, eval harness,
observability pipeline, and Kafka bus are all vertical-agnostic.

## Steps

1. **Define the vertical** — create `verticals/<name>/` package.
2. **Implement agents** — extend `BaseAgent`, declare `@AgentRole`.
3. **Register tools** — annotate Spring beans with `@Tool(vertical = "<name>")`.
4. **Add eval cases** — drop JSON files in `evals/<name>/`.
5. **Wire Kafka topics** — config in `application.yml`; topics
   `hivemind.<name>.*` are auto-created on startup.
6. **Done.** The platform handles routing, observability, scaling,
   evals.

## Example: planned CodeScout vertical

```java
@AgentRole(vertical = "codescout", role = "reviewer")
public class JavaReviewerAgent extends BaseAgent {
    @Override
    public AgentResult handle(AgentContext ctx) {
        // ... LLM call via LangChain4j, tool registry available
    }
}

@Tool(vertical = "codescout", name = "fetchPRDiff")
public DiffResult fetchPRDiff(String prUrl) { ... }

@Tool(vertical = "codescout", name = "postReviewComment")
public void postReviewComment(String prUrl, String path,
                              int line, String body) { ... }
```

Eval case (`evals/codescout/null-deref-001.json`):

```json
{
  "id": "null-deref-001",
  "input": {
    "diff": "...",
    "language": "java"
  },
  "expected": {
    "must_flag": ["NullPointerException risk on line 42"],
    "severity": "high"
  }
}
```

## What you get for free

- **Planner-driven orchestration** — describe steps; planner dispatches. ❌ not built — there's no
  planner; TriageBot's four stages are wired as fixed Kafka consumer→topic links, so a new vertical
  today would need the same kind of explicit wiring, not automatic dispatch from a description.
- **Kafka event bus** — durable, replayable, observable inter-agent
  communication. ✅ real, and genuinely vertical-agnostic (`EventBus`/`EventConsumer` don't know what
  a "ticket" is).
- **Tool runtime** — schema generation, timeout, retry, sandboxing. ⚠️ partial — timeout, retry, and
  virtual-thread sandboxing (`ToolInvoker`) are real; schema generation from method signatures isn't
  built.
- **Memory layer** — short-term Redis, long-term pgvector. ❌ not built — neither exists; the only
  persistent memory today is the Postgres audit log and the current-ticket-state table.
- **OpenTelemetry instrumentation** — traces and metrics emitted with
  `vertical` attribute pre-set. ⚠️ partial — HTTP, Kafka, and (as of 2026-08-06) LLM-call spans are
  real; tool-call spans aren't. A per-vertical attribute isn't pre-set on any of them today — nothing
  in `LlmClient` or `ToolInvoker` currently knows which vertical is calling — so this would still need
  real work to hold for a second vertical, not just configuration.
- **Eval harness** — same scoring framework, just add cases. ✅ real for the framework
  (`TriageEvalScorer` is pure logic with no triage-specific assumptions in its scoring rules), though
  `TriageEvalRunner`/`TriageEvalCase` are currently named and wired triage-specifically
  (deliberately — see `docs/PROJECT_STRUCTURE.md` on why `platform/eval/` doesn't exist yet); a
  second vertical would need its own runner, not just its own case files.
- **Autoscaling** — KEDA `ScaledObject` template generated per vertical. ❌ not built — no Kubernetes,
  no KEDA, no deployment automation at all yet.
- **Audit log** — every event, every tool call, every LLM call. ⚠️ partial — every *event* `EventBus`
  publishes is logged; that's a coarser grain than "every tool call, every LLM call" individually,
  which aren't separately recorded today.

## What's vertical-specific

- Agent prompts and roles
- Tool implementations
- Eval cases and gold answers
- Routing/policy logic (when to escalate, when to auto-resolve)
- Optional: vertical-specific schemas for inputs/outputs
