# Extending Hivemind: adding a new vertical

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

- **Planner-driven orchestration** — describe steps; planner dispatches.
- **Kafka event bus** — durable, replayable, observable inter-agent
  communication.
- **Tool runtime** — schema generation, timeout, retry, sandboxing.
- **Memory layer** — short-term Redis, long-term pgvector.
- **OpenTelemetry instrumentation** — traces and metrics emitted with
  `vertical` attribute pre-set.
- **Eval harness** — same scoring framework, just add cases.
- **Autoscaling** — KEDA `ScaledObject` template generated per vertical.
- **Audit log** — every event, every tool call, every LLM call.

## What's vertical-specific

- Agent prompts and roles
- Tool implementations
- Eval cases and gold answers
- Routing/policy logic (when to escalate, when to auto-resolve)
- Optional: vertical-specific schemas for inputs/outputs
