# Project structure

Hivemind is a Maven-based Spring Boot project. The package layout is
designed around **one rule**: the `platform/` package never depends on
any `verticals/` package. If you ever need to import from a vertical
into the platform to make something work, the abstraction is wrong —
fix the abstraction before adding the next vertical.

This file is the structural map — what lives where, and what's built vs.
still planned (`✅` = real code today, no mark = planned, described here
so the eventual file lands in the right place). For the deep "what does
this actually do, end to end, and why is it built this way," see
[../OVERVIEW.md](../OVERVIEW.md) — that doc explains behavior and
rationale; this one just explains layout.

## Top-level layout

```
Hivemind/
├── pom.xml                               ✅
├── README.md                             ✅
├── OVERVIEW.md                           ✅ living doc — the complete "what/why", updated every session
├── docker-compose.yml                    ✅ local dev Kafka (KRaft). Postgres + Redis added later
├── docs/
│   ├── ARCHITECTURE.md                   ✅
│   ├── EVALS.md                          ✅ (design only — harness not built yet)
│   ├── EXTENDING.md                      ✅
│   ├── PROJECT_STRUCTURE.md              ✅ this file
│   ├── INTERVIEW_PREP.md                 ✅ cumulative Q&A, topic-organized
│   └── devlog/                           ✅ one file per session, e.g. 2026-07-20.md
├── .github/
│   └── workflows/
│       ├── ci.yml                        # build + tests + evals — not added yet
│       └── deploy.yml                    # K8s deploy — not added yet
├── evals/                                # eval case JSON files — not added yet
│   └── triage/
├── k8s/                                  # not added yet
├── frontend/                             # Next.js dashboard — not added yet
└── src/
    ├── main/
    │   ├── java/com/hivemind/
    │   │   ├── HivemindApplication.java  ✅
    │   │   ├── platform/                 ✅ vertical-agnostic core
    │   │   ├── verticals/                ✅ vertical-specific code
    │   │   └── infra/                    ✅ Spring config, persistence wiring
    │   └── resources/
    │       ├── application.yml           ✅
    │       └── db/migration/             # Flyway migrations — not added yet (no DB yet)
    └── test/
        └── java/com/hivemind/            ✅ 9 test classes, 19 tests
```

## `com.hivemind.platform` — the vertical-agnostic core

```
platform/
├── agent/
│   ├── BaseAgent.java                    ✅ abstract base for all agents
│   ├── AgentRole.java                    ✅ @AgentRole annotation (meta-annotated @Component)
│   ├── AgentRegistry.java                # discovers @AgentRole beans by name — not needed yet, only one agent
│   ├── AgentContext.java                 ✅ request-scoped attribute bag
│   └── AgentResult.java                  ✅ success/payload/error, generic over agent output
├── tool/
│   ├── Tool.java                         ✅ @Tool annotation (meta-annotated @Component)
│   ├── ToolRegistry.java                 ✅ discovers @Tool beans, catalogs by name
│   ├── ToolInvoker.java                  ✅ virtual-thread sandbox, timeout, retry-on-timeout
│   └── ToolResult.java                   ✅ success/payload/error, mirrors AgentResult's shape
├── retry/
│   └── JitteredExponentialBackoff.java   ✅ shared backoff math (LlmClient + ToolInvoker)
├── planner/
│   └── PlannerAgent.java                 # generic planner — not built; only one agent exists so far
├── memory/
│   ├── ShortTermMemory.java              # Redis — not built
│   ├── LongTermMemory.java               # pgvector — not built
│   └── AuditLog.java                     # Postgres, immutable append-only — not built (TicketStatusStore is the in-memory stand-in today)
├── llm/
│   ├── LlmClient.java                    ✅ LangChain4j wrapper, retry/backoff, doChat test seam
│   └── CostTracker.java                  # tokens → USD — not built
├── messaging/
│   ├── EventBus.java                     ✅ Kafka producer wrapper (JSON via Jackson)
│   ├── EventConsumer.java                # generic consumer base — deliberately not built; only one concrete consumer exists so far, see OVERVIEW.md
│   └── TopicNaming.java                  ✅ hivemind.<vertical>.<stage> convention helper
├── observability/
│   ├── OtelConfig.java                   # not built
│   └── MetricsRecorder.java              # not built
└── eval/
    ├── EvalCase.java                     # not built
    ├── EvalRunner.java                   # not built
    ├── EvalScorer.java                   # not built
    └── EvalReport.java                   # not built
```

**Rule**: nothing in `platform/` may reference `com.hivemind.verticals.*`.

## `com.hivemind.verticals` — vertical-specific code

Each vertical owns a sibling subpackage. Only `triage/` exists so far.

```
verticals/
└── triage/
    ├── TriageController.java             ✅ POST /api/v1/triage/tickets (202), GET /tickets/{id}
    ├── TicketStatusStore.java            ✅ in-memory read model — stand-in for platform/memory/AuditLog
    ├── agents/
    │   ├── ClassifierAgent.java          ✅ @AgentRole(vertical="triage", role="classifier")
    │   ├── RetrieverAgent.java           # not built — next candidate, would consume hivemind.triage.classified
    │   └── ResponderAgent.java           # not built
    ├── messaging/
    │   ├── TriageTopics.java             ✅ CLASSIFY / CLASSIFIED topic name constants
    │   └── ClassifyRequestConsumer.java  ✅ @KafkaListener, runs ClassifierAgent, publishes result
    ├── events/
    │   └── ClassifyRequested.java        ✅ {ticketId, body} — the classify-request payload
    ├── kb/
    │   ├── KbChunk.java                  ✅ {id, title, text}
    │   └── KnowledgeBase.java            ✅ 5 hardcoded chunks — stand-in for a Postgres-backed KB
    ├── tools/
    │   ├── SearchKbTool.java             ✅ @Tool(vertical="triage", name="searchKb"), naive keyword scoring
    │   ├── SearchPastTicketsTool.java    # not built
    │   ├── FetchUserHistoryTool.java     # not built
    │   ├── EscalateToHumanTool.java      # not built
    │   └── SendResponseTool.java         # not built
    └── model/
        ├── Ticket.java                   ✅ inbound DTO {body}
        ├── Category.java                 ✅ BILLING/BUG/FEATURE_REQUEST/ABUSE/OTHER
        ├── Classification.java           ✅ {category, confidence}
        ├── TriageResponse.java           ✅ outbound DTO — also the Kafka "classified" event payload
        ├── RoutingDecision.java          # not built
        └── DraftResponse.java            # not built
```

When CodeScout (v2) and DeepDigger (v3) ship, they'll be siblings under
`verticals/`. They will not modify any `platform/` code.

## `com.hivemind.infra` — wires and persistence

```
infra/
├── config/
│   ├── KafkaConfig.java                  ✅ NewTopic beans for triage's two topics
│   ├── ClaudeConfig.java                 ✅ LangChain4j AnthropicChatModel bean
│   ├── PostgresConfig.java               # not built — no DB yet
│   └── RedisConfig.java                  # not built
└── persistence/
    ├── TicketRepository.java             # not built
    ├── AuditEventRepository.java         # not built
    └── KbChunkRepository.java            # not built (pgvector)
```

`infra/` is "wire it up to Spring/DB/Kafka". `platform/` is the domain
logic. Mixing the two produces unmaintainable code; keep them separate.
Note `KafkaConfig` currently imports `TriageTopics` from `verticals.triage`
— `infra/` is explicitly allowed to know about specific verticals (it's
wiring, not domain logic), unlike `platform/`.

## Resources

```
resources/
├── application.yml                       ✅ spring.kafka.*, hivemind.llm.*, hivemind.tool.*, hivemind.eval.* (eval thresholds configured but unused — no harness yet)
├── application-local.yml                 # not added yet
├── application-test.yml                  # not added yet
└── db/migration/                         # Flyway migrations — not added yet (no DB yet)
```

## Evals — outside `src/`

Eval cases are **data**, not source code. They'll live in `evals/<vertical>/`
at the repo root so they can be edited by humans, possibly by non-engineers
later, without classpath bloat or recompilation. Not built yet — thresholds
are configured in `application.yml` (`hivemind.eval.thresholds.*`) but
nothing reads them.

## Frontend — separate sibling

The Next.js dashboard lives in `frontend/` at the repo root, once built.
It is a sibling of the Spring Boot app, not a Spring resource. It builds
and deploys independently.

## Current state (as of 2026-07-20)

Real code exists in `platform/agent`, `platform/llm`, `platform/messaging`,
`platform/tool`, `platform/retry`, all of `verticals/triage` except the
Retriever/Responder agents and four of the five planned tools, and
`infra/config/{KafkaConfig,ClaudeConfig}`. Local Kafka runs via
`docker-compose.yml`. Nothing under `k8s/`, `frontend/`, `evals/`,
`db/migration/`, or `.github/workflows/` exists yet — those are still
purely descriptions, filled in as their sessions come up.

For the full narrative — what each of these pieces actually does, the
request lifecycle as it genuinely runs today, and the reasoning behind
every structural choice above — see [../OVERVIEW.md](../OVERVIEW.md).
Day-by-day history of how it got here lives in [devlog/](devlog/).
