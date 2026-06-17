# Project structure

Hivemind is a Maven-based Spring Boot project. The package layout is
designed around **one rule**: the `platform/` package never depends on
any `verticals/` package. If you ever need to import from a vertical
into the platform to make something work, the abstraction is wrong —
fix the abstraction before adding the next vertical.

## Top-level layout

```
Hivemind/
├── pom.xml
├── README.md
├── docker-compose.yml                    # local dev: Kafka + Postgres + Redis (added later)
├── docs/
│   ├── ARCHITECTURE.md
│   ├── EVALS.md
│   ├── EXTENDING.md
│   └── PROJECT_STRUCTURE.md              # this file
├── .github/
│   └── workflows/
│       ├── ci.yml                        # build + tests + evals
│       └── deploy.yml                    # K8s deploy (week 3)
├── evals/
│   └── triage/
│       ├── billing-001.json
│       └── ...
├── k8s/                                  # week 3
│   ├── api-gateway.yaml
│   ├── classifier-agent.yaml
│   └── keda-scaledobject.yaml
├── frontend/                             # Next.js 15 dashboard, week 3
│   └── ...
└── src/
    ├── main/
    │   ├── java/com/hivemind/
    │   │   ├── HivemindApplication.java
    │   │   ├── platform/                 # vertical-agnostic core
    │   │   ├── verticals/                # vertical-specific code
    │   │   └── infra/                    # Spring config, persistence wiring
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/             # Flyway migrations
    └── test/
        └── java/com/hivemind/
```

## `com.hivemind.platform` — the vertical-agnostic core

```
platform/
├── agent/
│   ├── BaseAgent.java                    # abstract base for all agents
│   ├── AgentRole.java                    # @AgentRole annotation
│   ├── AgentRegistry.java                # discovers @AgentRole-annotated beans
│   ├── AgentContext.java                 # request-scoped state
│   └── AgentResult.java
├── tool/
│   ├── Tool.java                         # @Tool annotation
│   ├── ToolRegistry.java                 # discovers @Tool-annotated beans
│   ├── ToolInvoker.java                  # timeout, retry, sandbox
│   └── ToolResult.java
├── planner/
│   └── PlannerAgent.java                 # generic planner; vertical-agnostic
├── memory/
│   ├── ShortTermMemory.java              # Redis
│   ├── LongTermMemory.java               # pgvector
│   └── AuditLog.java                     # Postgres (immutable, append-only)
├── llm/
│   ├── LlmClient.java                    # LangChain4j wrapper
│   └── CostTracker.java                  # tokens → USD per request
├── messaging/
│   ├── EventBus.java                     # Kafka producer
│   ├── EventConsumer.java                # Kafka consumer base
│   └── TopicNaming.java                  # hivemind.<vertical>.<stage>
├── observability/
│   ├── OtelConfig.java
│   └── MetricsRecorder.java
└── eval/
    ├── EvalCase.java
    ├── EvalRunner.java                   # loads cases, dispatches to vertical
    ├── EvalScorer.java
    └── EvalReport.java
```

**Rule**: nothing in `platform/` may reference `com.hivemind.verticals.*`.

## `com.hivemind.verticals` — vertical-specific code

Each vertical owns a sibling subpackage. The MVP ships only `triage/`.

```
verticals/
└── triage/
    ├── TriageController.java             # POST /api/v1/triage/tickets
    ├── agents/
    │   ├── ClassifierAgent.java          # @AgentRole(vertical="triage", role="classifier")
    │   ├── RetrieverAgent.java
    │   └── ResponderAgent.java
    ├── tools/
    │   ├── SearchKbTool.java             # @Tool(vertical="triage", name="searchKb")
    │   ├── SearchPastTicketsTool.java
    │   ├── FetchUserHistoryTool.java
    │   ├── EscalateToHumanTool.java
    │   └── SendResponseTool.java
    └── model/
        ├── Ticket.java
        ├── Category.java
        ├── RoutingDecision.java
        └── DraftResponse.java
```

When CodeScout (v2) and DeepDigger (v3) ship, they'll be siblings under
`verticals/`. They will not modify any `platform/` code.

## `com.hivemind.infra` — wires and persistence

```
infra/
├── config/
│   ├── KafkaConfig.java
│   ├── PostgresConfig.java
│   ├── RedisConfig.java
│   └── ClaudeConfig.java
└── persistence/
    ├── TicketRepository.java
    ├── AuditEventRepository.java
    └── KbChunkRepository.java            # pgvector
```

`infra/` is "wire it up to Spring/DB/Kafka". `platform/` is the domain
logic. Mixing the two produces unmaintainable code; keep them separate.

## Resources

```
resources/
├── application.yml                       # default profile
├── application-local.yml                 # local dev overrides (added when needed)
├── application-test.yml
└── db/migration/                         # Flyway migrations
    ├── V1__init.sql
    ├── V2__audit_log.sql
    └── V3__pgvector.sql
```

## Evals — outside `src/`

Eval cases are **data**, not source code. They live in `evals/<vertical>/`
at the repo root so they can be edited by humans, possibly by non-engineers
later, without classpath bloat or recompilation.

## Frontend — separate sibling

The Next.js 15 dashboard (week 3) lives in `frontend/` at the repo root.
It is a sibling of the Spring Boot app, not a Spring resource. It builds
and deploys independently.

## Day 1 scaffold (current state)

Only the minimum-to-compile-and-run files are present today:

- `pom.xml`, `application.yml`, `.gitignore`
- `HivemindApplication.java`
- `platform/agent/AgentRole.java` (annotation only — no registry yet)
- `platform/tool/Tool.java` (annotation only — no registry yet)
- `verticals/triage/TriageController.java` (stub `POST /api/v1/triage/tickets`)
- `verticals/triage/model/Ticket.java`
- `HivemindApplicationTests.java` (context-loads test)

The rest of the tree is filled in across weeks 1–3 per the roadmap in
[../README.md](../README.md).
