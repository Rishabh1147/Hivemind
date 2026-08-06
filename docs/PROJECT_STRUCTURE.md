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
[../docs/ARCHITECTURE.md](ARCHITECTURE.md) (git-tracked, for anyone
reading the repo) — there's also a root-level `OVERVIEW.md`, but that one
is a personal, gitignored prep doc, not part of the repo for other readers.

## Top-level layout

```
Hivemind/
├── pom.xml                               ✅
├── README.md                             ✅
├── OVERVIEW.md                           (gitignored — personal prep doc, not part of the tracked repo)
├── docker-compose.yml                    ✅ local dev Kafka (KRaft) + Postgres. Redis added later
├── docs/
│   ├── ARCHITECTURE.md                   ✅
│   ├── EVALS.md                          ✅ design doc — harness now built (verticals/triage/eval/), 53 of the 50+ target cases exist
│   ├── EXTENDING.md                      ✅
│   ├── PROJECT_STRUCTURE.md              ✅ this file
│   ├── INTERVIEW_PREP.md                 ✅ cumulative Q&A, topic-organized
│   └── devlog/                           ✅ one file per session, e.g. 2026-07-20.md
├── .github/
│   └── workflows/
│       ├── ci.yml                        ✅ build + 43 tests on every push/PR to main — evals deliberately not gated here yet (cost/secrets, see EVALS.md)
│       └── deploy.yml                    # K8s deploy — not added yet
├── evals/
│   └── triage/                           ✅ 53 hand-written *.json cases (target 50+ met, per docs/EVALS.md)
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
    │       └── db/migration/             ✅ V1__create_tickets_table.sql, V2__create_audit_events_table.sql
    └── test/
        └── java/com/hivemind/            ✅ 15 test classes, 43 tests (plus a shared AbstractPostgresIntegrationTest base, not a test class itself)
```

## `com.hivemind.platform` — the vertical-agnostic core

```
platform/
├── agent/
│   ├── BaseAgent.java                    ✅ abstract base for all agents
│   ├── AgentRole.java                    ✅ @AgentRole annotation (meta-annotated @Component)
│   ├── AgentRegistry.java                # discovers @AgentRole beans by name — not needed yet, agents are still wired by direct injection
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
│   └── PlannerAgent.java                 # generic planner — not built; the 4-agent triage pipeline is still four fixed Kafka hops, not planner-dispatched
├── memory/
│   ├── ShortTermMemory.java              # Redis — not built
│   ├── LongTermMemory.java               # pgvector — not built
│   └── AuditLog.java                     ✅ Postgres, immutable append-only. JdbcTemplate-backed; EventBus.publish() writes here alongside every Kafka send
├── llm/
│   ├── LlmClient.java                    ✅ LangChain4j wrapper, retry/backoff, doChat test seam — returns LlmResponse, not String
│   ├── LlmResponse.java                  ✅ {text, tokenUsage} — what LlmClient.complete() actually returns
│   └── CostTracker.java                  ✅ tokens → USD at a configurable (placeholder) per-million-token rate
├── messaging/
│   ├── EventBus.java                     ✅ Kafka producer wrapper (JSON via Jackson)
│   ├── EventConsumer.java                ✅ generic consumer base — deserialize, catch-and-log onEvent; all four triage consumers extend it
│   └── TopicNaming.java                  ✅ hivemind.<vertical>.<stage> convention helper
├── observability/
│   ├── OtelConfig.java                   # not built
│   └── MetricsRecorder.java              # not built
└── eval/                                  # not built here — see verticals/triage/eval/ below
```

**`platform/eval/` deliberately doesn't exist yet**, even though this file originally sketched
`EvalCase`/`EvalRunner`/`EvalScorer`/`EvalReport` as platform-level. With only one vertical, a
generic contract would be guessing at what varies across verticals from a single example — the same
discipline that kept `ToolRegistry`, `EventConsumer`, and `JitteredExponentialBackoff` unbuilt or
un-extracted until a second real case existed. The eval harness was built triage-specific instead
(`verticals/triage/eval/`, below); generalizing into `platform/eval/` is deferred until CodeScout
(v2) needs eval scoring too.

**Rule**: nothing in `platform/` may reference `com.hivemind.verticals.*`.

## `com.hivemind.verticals` — vertical-specific code

Each vertical owns a sibling subpackage. Only `triage/` exists so far.

```
verticals/
└── triage/
    ├── TriageController.java             ✅ POST /api/v1/triage/tickets (202), GET /tickets/{id}
    ├── agents/
    │   ├── ClassifierAgent.java          ✅ @AgentRole(vertical="triage", role="classifier")
    │   ├── RetrieverAgent.java           ✅ @AgentRole(vertical="triage", role="retriever"), calls searchKb via ToolRegistry+ToolInvoker
    │   ├── ResponderAgent.java           ✅ @AgentRole(vertical="triage", role="responder"), drafts answer + citations via LlmClient
    │   ├── RoutingAgent.java             ✅ @AgentRole(vertical="triage", role="router"), deterministic policy — no LlmClient call
    │   └── TriageContextKeys.java        ✅ shared AgentContext keys (ticketBody, retrievedChunks, currentTriageResponse) — used by all four agents above
    ├── messaging/
    │   ├── TriageTopics.java             ✅ CLASSIFY / CLASSIFIED / RETRIEVED / RESPONDED / ROUTED topic name constants
    │   ├── ClassifyRequestConsumer.java  ✅ extends EventConsumer, runs ClassifierAgent, publishes TicketClassified
    │   ├── TicketClassifiedConsumer.java ✅ extends EventConsumer, runs RetrieverAgent, publishes TicketRetrieved
    │   ├── TicketRetrievedConsumer.java  ✅ extends EventConsumer, runs ResponderAgent, publishes TicketResponded, writes TicketRepository
    │   └── TicketRespondedConsumer.java  ✅ extends EventConsumer, runs RoutingAgent, publishes TicketRouted, writes TicketRepository (never skips, even on response_failed)
    ├── events/
    │   ├── ClassifyRequested.java        ✅ {ticketId, body} — the classify-request payload
    │   ├── TicketClassified.java         ✅ {ticketId, ticketBody, status, category, confidence, error} — classify result, carries body for the retrieve stage
    │   ├── TicketRetrieved.java          ✅ {ticketId, ticketBody, status, chunks, error} — retrieve result, carries body for the respond stage
    │   ├── TicketResponded.java          ✅ {ticketId, status, answer, citedChunkIds, error} — respond result
    │   └── TicketRouted.java             ✅ {ticketId, status, routingDecision, error} — final route result
    ├── eval/
    │   ├── TriageEvalCase.java            ✅ {id, ticket, expectedCategory, expectedRouting, mustCite} — loaded from evals/triage/*.json
    │   ├── TriageEvalResult.java          ✅ one case's scored outcome
    │   ├── TriageEvalReport.java          ✅ aggregate accuracy/recall/latency, written to eval-results/<timestamp>.json
    │   ├── TriageEvalScorer.java          ✅ pure comparison logic, unit-tested with no agents/mocks
    │   ├── TriageEvalRunner.java          ✅ runs all 4 agents in-process per case (no Kafka — evals score model quality, not event-bus plumbing)
    │   └── TriageEvalHarnessRunner.java   ✅ CommandLineRunner, @Profile("eval"), gates on hivemind.eval.thresholds.*, real exit code
    ├── kb/
    │   ├── KbChunk.java                  ✅ {id, title, text}
    │   └── KnowledgeBase.java            ✅ 5 hardcoded chunks — stand-in for a Postgres-backed KB
    ├── tools/
    │   ├── SearchKbTool.java             ✅ @Tool(vertical="triage", name="searchKb"), naive keyword scoring, called by RetrieverAgent
    │   ├── SearchPastTicketsTool.java    # not built
    │   ├── FetchUserHistoryTool.java     # not built
    │   ├── EscalateToHumanTool.java      # not built
    │   └── SendResponseTool.java         # not built
    └── model/
        ├── Ticket.java                   ✅ inbound DTO {body}
        ├── Category.java                 ✅ BILLING/BUG/FEATURE_REQUEST/ABUSE/OTHER
        ├── Classification.java           ✅ {category, confidence}
        ├── DraftResponse.java            ✅ {answer, citedChunkIds} — ResponderAgent output
        ├── RoutingDecision.java          ✅ AUTO_RESOLVE/QUEUE_FOR_HUMAN/ESCALATE — RoutingAgent output
        └── TriageResponse.java           ✅ outbound DTO, progressively enriched (pending → classified → responded → routed) via with*() methods
```

Every file originally sketched in the Day-1 scaffold plan under `verticals/triage/agents/` and
`verticals/triage/model/` is now built — the four planned-but-unbuilt tool files above are the only
remaining placeholders in this vertical.

When CodeScout (v2) and DeepDigger (v3) ship, they'll be siblings under
`verticals/`. They will not modify any `platform/` code.

## `com.hivemind.infra` — wires and persistence

```
infra/
├── config/
│   ├── KafkaConfig.java                  ✅ NewTopic beans for triage's five topics
│   ├── ClaudeConfig.java                 ✅ LangChain4j AnthropicChatModel bean
│   ├── TracingConfig.java                ✅ LoggingSpanExporter bean — the only wiring OTel tracing needs; Tracer/Propagator are autoconfigured
│   ├── PostgresConfig.java               # not built — spring-boot-starter-jdbc autoconfigures the DataSource/JdbcTemplate, nothing custom needed yet
│   └── RedisConfig.java                  # not built
└── persistence/
    ├── TicketRepository.java             ✅ JdbcTemplate-backed current-state store, replaces the old in-memory TicketStatusStore
    ├── AuditEventRepository.java         # not built — AuditLog (platform/memory/) covers this role directly for now
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
├── application.yml                       ✅ spring.kafka.*, spring.datasource.*, spring.flyway.*, hivemind.llm.*, hivemind.tool.*, hivemind.eval.* (thresholds now read by TriageEvalHarnessRunner)
├── application-local.yml                 # not added yet
├── application-test.yml                  # not added yet — tests override datasource/kafka props via Testcontainers instead
└── db/migration/
    ├── V1__create_tickets_table.sql      ✅ current-state read model
    └── V2__create_audit_events_table.sql ✅ immutable append-only log
```

## Evals — outside `src/`

Eval cases are **data**, not source code. They live in `evals/<vertical>/`
at the repo root so they can be edited by humans, possibly by non-engineers
later, without classpath bloat or recompilation — `evals/triage/` has 53
cases today, meeting the 50+ target. Run cases with
`./mvnw spring-boot:run -Dspring-boot.run.profiles=eval`; results land in
`eval-results/<timestamp>.json` (gitignored). Thresholds in `application.yml`
(`hivemind.eval.thresholds.*`) are read and gated on by
`TriageEvalHarnessRunner` — `category-accuracy`, `citation-recall`,
`p95-latency-ms`, and (as of session 14) `cost-per-ticket-usd`; only
`tone-min-avg` stays configured but ungated (see `docs/EVALS.md` for why).

## Frontend — separate sibling

The Next.js dashboard lives in `frontend/` at the repo root, once built.
It is a sibling of the Spring Boot app, not a Spring resource. It builds
and deploys independently.

## Current state (as of 2026-08-05, session 14)

Real code exists in `platform/agent`, `platform/llm` (including `LlmResponse`/`CostTracker`),
`platform/messaging` (including the generic `EventConsumer` base and `AuditLog`), `platform/tool`,
`platform/retry`, all of `verticals/triage` except four planned tool files, `infra/{config,persistence}`
(including `TracingConfig`), and `.github/workflows/ci.yml`. Local Kafka and Postgres both run via
`docker-compose.yml` — Kafka carrying four chained consumers (classify → retrieve → respond → route)
with a single trace id propagated via Kafka headers across every hop, Postgres backing both
`TicketRepository` (current state) and `AuditLog` (immutable event history). `evals/triage/` has 53
real cases (the 50+ target, met session 13), run via the `eval` Spring profile and gated on four of
five configured thresholds. GitHub Actions CI runs the 43-test suite on every push/PR — not yet the
eval harness itself. Nothing under `k8s/` or `frontend/` exists yet — those are still purely
descriptions, filled in if/when their sessions come up.

For the full narrative — what each of these pieces actually does, the
request lifecycle as it genuinely runs today, and the reasoning behind
every structural choice above — see [ARCHITECTURE.md](ARCHITECTURE.md)
(git-tracked) or the root `OVERVIEW.md` (personal, gitignored, not visible
to other readers of this repo). Day-by-day history lives in [devlog/](devlog/).
