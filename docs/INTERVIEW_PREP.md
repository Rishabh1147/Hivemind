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
credibility in an interview. As of the last devlog (2026-07-21): two agents (`ClassifierAgent`,
`RetrieverAgent`) genuinely chained through Kafka — classify feeds retrieve — with
`ToolRegistry`/`ToolInvoker` and the `searchKb` tool now actually called in the live pipeline, not
just proven standalone. Still not built: a planner that *decides* which agents to invoke (the
classify→retrieve link is hardcoded in a consumer, not planner-routed), a Responder (retrieved
chunks aren't surfaced anywhere user-visible yet), Postgres persistence (an in-memory
`TicketStatusStore` stands in for now), eval harness, OpenTelemetry, frontend. Check
`docs/devlog/` for the current honest state before claiming anything more specific.

## LLM / agent orchestration

**Q: Walk me through what happens when a ticket comes in, today.**
`POST /api/v1/triage/tickets` → `TriageController` generates a ticket ID, writes a `pending`
`TriageResponse` into `TicketStatusStore`, publishes a `ClassifyRequested` event to
`hivemind.triage.classify` via `EventBus`, and returns immediately with `202 Accepted` — the
request thread never waits on Claude. Separately, `ClassifyRequestConsumer` (`@KafkaListener` on
that topic) picks up the event, builds an `AgentContext`, hands it to `ClassifierAgent.handle()` —
which sends the ticket body to Claude via `LlmClient` with a system prompt demanding strict JSON,
parses the response into a `Category` + confidence with Jackson, and returns an `AgentResult`
(success/payload or failure/error message). The consumer turns that into a `TriageResponse`,
publishes a `TicketClassified` event (which carries the ticket body — `TriageResponse` doesn't) to
`hivemind.triage.classified`, and writes a `TriageResponse` into `TicketStatusStore`. The client
polls `GET /api/v1/triage/tickets/{id}` to see the result — `pending` until the consumer catches up,
then `classified` or `classification_failed`.

Separately again, `TicketClassifiedConsumer` picks up that same `TicketClassified` event. If
classification failed it stops there — nothing to search for. Otherwise it hands the ticket body to
`RetrieverAgent`, which looks up `searchKb` from `ToolRegistry` and calls it through `ToolInvoker`;
the resulting chunks get published as a `TicketRetrieved` event to `hivemind.triage.retrieved`. This
stage doesn't touch `TicketStatusStore` — there's no Responder yet to turn retrieved chunks into
something the API should show, so `GET /tickets/{id}` still reports `classified`, and the retrieved
result is, for now, only observable on the topic itself.

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
Two layers. `ClassifierAgent` wraps the LLM call in try/catch and returns `AgentResult.failure(...)`
rather than letting the exception propagate — found and fixed a real bug here early on (see the
2026-07-15 devlog) where that wrapping was initially missing and a real `AuthenticationException`
surfaced as an unhandled 500. Below that, `LlmClient.complete()` retries transient failures with
exponential backoff + full jitter (max 3 attempts by default) before the agent ever sees an
exception — but only for failures worth retrying. Non-retriable failures (bad API key, malformed
request) fail immediately with no retry delay.

**Q: How do you decide what's "worth retrying"?**
Didn't have to invent this — LangChain4j already ships the classification as a proper exception
hierarchy: `RetriableException` (parent of `RateLimitException`, `InternalServerException`,
`TimeoutException` — transient, provider-side, safe to retry) vs. `NonRetriableException` (parent
of `AuthenticationException`, `InvalidRequestException`, `ModelNotFoundException` — retrying won't
help, the request itself is wrong). The retry loop in `LlmClient` just catches `RetriableException`
specifically; anything else propagates on the first attempt. Verified this against the *real*
Anthropic API, not just a mock: hit the live endpoint with a deliberately invalid key and confirmed
the response came back in well under a second — no wasted retry delay on a failure retrying can't
fix.

**Q: What's "full jitter" backoff and why use it over plain exponential backoff?**
Plain exponential backoff (500ms, 1s, 2s, ...) means every client hitting the same failure at the
same time retries at the same moments — a thundering herd against a provider that's already
struggling. Full jitter picks a *random* delay between 0 and the exponential ceiling for that
attempt, so concurrent retries spread out instead of re-synchronizing. `LlmClient` implements this
as `baseBackoffMs * 2^(attempt-1)`, then a random value in `[0, that]`.

**Q: Why does `LlmClient` have a `protected doChat(...)` method that just wraps one line?**
Came out of a test-design problem, not a design-first decision. First pass at testing the retry
loop tried mocking LangChain4j's `ChatModel`/`ChatResponse` with Mockito — `ChatResponse` isn't
built to be mocked (stubbing it threw `UnfinishedStubbingException`, Mockito's signal that a real
method ran instead of being intercepted). Rather than fight the SDK's internal response type,
pulled the one line that actually touches `ChatModel` into its own method, and tested the retry
*policy* with a small subclass that overrides that method to throw or return scripted values —
zero LangChain4j types in the test. When a test is awkward to write, that's usually telling you
something about the code's seams, not about the test.

**Q: Why does the classifier ask for JSON via prompt instructions instead of using tool-calling /
structured outputs?**
Simplest thing that works for a single-field classification task today. Tool-calling schemas and
LangChain4j's structured-output support are the natural upgrade once there's more than one
extracted field or the JSON-parse-failure rate in practice justifies it — deliberately not adding
that abstraction before there's a concrete need for it.

## Kafka / eventing

**Q: Why does the ticket endpoint return `202 Accepted` instead of `200 OK` with the classification
now?**
That's the concrete tradeoff of putting Kafka between the controller and the agent: the request
thread publishes an event and returns, it doesn't block on Claude anymore. `202` with a `pending`
status is the honest HTTP status for "accepted, not yet done" — returning `200` with a result that
doesn't exist yet would be wrong. `GET /api/v1/triage/tickets/{id}` is the interim way to observe
progress until the `Stream` step in `ARCHITECTURE.md` (SSE to a dashboard) exists — swapping that in
later only changes the read side, not the event-bus wiring.

**Q: Why publish `TicketClassified`-shaped events (`hivemind.triage.classified`) if nothing
consumes them yet?**
This was true through 2026-07-20 and is the reasoning that held while it was: the event log is
meant to be the audit trail (`ARCHITECTURE.md`: "the event log *is* the audit log"), and publishing
a topic's contract one stage ahead of having a consumer means it's designed alongside the producer
instead of guessed at later from the consumer side. As of 2026-07-21 it's no longer hypothetical —
`TicketClassifiedConsumer` consumes exactly this topic, and `hivemind.triage.retrieved` is now the
one published a stage ahead of its own first consumer.

**Q: `TicketClassified` and `TriageResponse` used to be the same type reused for two purposes — why
split them?**
Because they stopped actually being the same information. While only the HTTP response needed
`{id, status, category, confidence, error}`, reusing `TriageResponse` as the Kafka payload too was
harmless — no duplication, just one type serving two roles that happened to need the same fields.
That coincidence broke the moment `RetrieverAgent` needed the original ticket body to search
against: an HTTP client has no reason to get its own submitted ticket body echoed back in the
response, but a downstream Kafka consumer has every reason to need it, since it's not the one that
originally received the ticket. The fix is a dedicated `TicketClassified` event type carrying
`ticketBody`, not adding an unused field to the API response. General lesson: two things that
happen to have the same shape aren't the same concept, and the right time to notice is when a real
second consumer's actual needs diverge — not by trying to anticipate it upfront.

**Q: Where does `EventBus` fit relative to `LlmClient`?**
Same shape, same reason. `LlmClient` is the one seam every vertical talks to Claude through;
`EventBus` is the one seam every vertical publishes Kafka events through — both wrap a
provider/client library (LangChain4j's `ChatModel`, Spring Kafka's `KafkaTemplate`) so
cross-cutting concerns (OTel spans, an outbox pattern for buffering when Kafka's down — both on the
roadmap) get added in one place instead of in every agent or consumer.

**Q: How are topics named, and why declare them explicitly instead of letting the broker
auto-create them?**
Convention is `hivemind.<vertical>.<stage>` (`platform/messaging/TopicNaming.java`), enforced by a
test (`TopicNamingTest`) rather than by the type system — `@KafkaListener(topics = ...)` requires a
compile-time constant, and `TopicNaming.of(...)` is a method call, so it can't be used directly in
the annotation. Vertical topic names live as literal constants (`TriageTopics`) instead, with the
test as the guardrail against drift. Topics themselves are declared as `NewTopic` beans in
`KafkaConfig` (3 partitions, replication factor 1 for local dev) rather than relying on
`auto.create.topics.enable`, which production clusters typically turn off — explicit beans mean
partition count and replication factor are in version control, not implicit broker config.

**Q: Why didn't you build a generic `EventConsumer` base class, if `PROJECT_STRUCTURE.md` sketches
one?**
Through 2026-07-20, only one concrete consumer existed (`ClassifyRequestConsumer`), and extracting a
shared deserialize/error-handling shape from a single example would have been guessing at what
varies and what doesn't — the same reasoning that kept `ToolRegistry` unbuilt until there was a
second tool. As of 2026-07-21 there are two consumers (`ClassifyRequestConsumer`,
`TicketClassifiedConsumer`) with an identical shape — try/catch around
`objectMapper.readValue`, log-and-return on failure, build an `AgentContext`, call an agent, publish
the result. The extraction is no longer speculative; it just hasn't been done yet because this
session's focus was making the second consumer exist for real, not generalizing immediately as a
side effect. It's the next flagged candidate for exactly this reason.

**Q: How does a Kafka listener fail without silently dropping messages forever?**
Today: it doesn't have a dead-letter topic yet, so a message that can't be deserialized is logged
and dropped (fails loudly in logs, not the request), and any exception from the classifier itself
never reaches the listener because `ClassifierAgent.handle()` already can't throw — every failure
path returns `AgentResult.failure(...)`, which becomes a `classification_failed` status instead of
an uncaught exception. That's deliberate reuse of failure handling already built and tested (see
2026-07-15/16 devlogs) rather than duplicating it at the Kafka layer. A real dead-letter-topic +
retry policy is future work once there's a second consumer to justify the shared shape (see above).

**Q: You could have wired this with an in-memory queue or just a `@Async` method — why actually
stand up Kafka?**
Because the properties that make Kafka worth the operational cost — replay from the event log,
per-agent horizontal scaling via consumer groups, the event log doubling as an audit trail, strict
per-vertical topic isolation — don't exist with an in-process queue, and pretending they do would
misrepresent what's built. The whole point of this session was to make "dispatch via Kafka" an
honest claim, not an approximately-similar one.

**Q: What proved this actually works, versus just compiling?**
Three layers, each catching something the previous one couldn't: `TopicNamingTest` (a unit test)
checks the naming convention purely in-process. `TriageKafkaIntegrationTest` runs a full
producer → real Kafka broker (Testcontainers) → consumer → status-store round trip, with
`ClassifierAgent` mocked so the test isolates the event-bus mechanics from classification logic.
Then, separately, the real app was started against the `docker-compose` broker and hit with `curl`
— `POST` returned `202`/`pending` immediately, a later `GET` showed `classification_failed` with
the real Anthropic auth error message (proving the consumer actually invoked Claude, not a stub),
and `kafka-topics.sh --describe` on the broker confirmed the topics existed with the exact
partition count `KafkaConfig`'s beans declare. Each layer answers a question the one before it
can't: "is the logic right," "does it work against a real broker," "does it work as the actual
deployed app would run."

**Q: Any real bugs or surprises building this?**
One, and it was in the *tooling*, not the app code: `mvn test` failed only on the new Testcontainers
test, with the shaded Docker client sending API version `1.32` against a daemon whose minimum
supported version is `1.40` — confirmed via `curl` on the daemon's `/version` endpoint directly that
the real API version was `1.55`, so `1.32` wasn't a negotiated value, just a stale default somewhere
in the client. Opened the actual shaded `RemoteApiVersion` class from the Testcontainers jar to
check what versions it even knew about (`1.44` was its ceiling) rather than guessing, then bumped
`testcontainers.version` from `1.20.4` to `1.21.4`, which fixed it outright. The lesson worth
repeating in an interview: when a test framework — not your code — looks broken, verify that
specifically before reaching for a workaround; a version bump only looks "obvious" in hindsight
after confirming where the mismatch actually was.

A second one on 2026-07-21: `KafkaTestUtils.consumerProps(...)` takes `(brokerAddresses, group,
autoCommit)`; called it with the arguments in the order they'd read naturally
(`group, autoCommit, brokerAddresses`), which compiled fine (all three are `String`) and failed at
runtime with `ConfigException: Invalid value PLAINTEXT://... for enable.auto.commit`. Fixed by
checking the actual method signature instead of re-guessing the order. Small, but a reminder that
same-typed positional parameters are a real footgun even in library code you didn't write.

**Q: How does `TicketClassifiedConsumer` decide whether to run retrieval, and how was that verified
without a real Anthropic API key?**
It checks the incoming `TicketClassified` event's `status` field — `"classified"` proceeds to
retrieval, anything else (`"classification_failed"`) logs and returns without publishing anything to
`hivemind.triage.retrieved`. There's no point searching a knowledge base for a ticket whose category
is unknown. Verified against the real `docker-compose` broker without needing a working Anthropic
key by publishing hand-built `TicketClassified` JSON messages directly onto
`hivemind.triage.classified` with `kafka-console-producer.sh` — one with `status: "classified"`,
which produced a real, correctly-ranked result on the retrieved topic
(`kafka-console-consumer.sh`), and one with `status: "classification_failed"`, which produced
nothing, with the skip reason visible in the app log. This isolates exactly the new code
(`TicketClassifiedConsumer` → `RetrieverAgent` → `ToolRegistry`/`ToolInvoker` → `SearchKbTool`) from
the classify stage, the same way `@MockBean`-ing `ClassifierAgent` isolates it in the automated
integration test.

## Tool registry

**Q: Why does `ToolInvoker` retry on timeout but not on an exception the tool throws?**
They're different failure classes. A timeout is infra-level and transient — the same reasoning as
`LlmClient` retrying `RetriableException` — so backing off and trying again can help. An exception
the tool itself throws is a logic error (bad input, a bug, a downstream 4xx) that will fail the
same way every time; retrying it just delays the failure. `LlmClient` gets this distinction for
free from LangChain4j's exception hierarchy; `ToolInvoker` doesn't have an SDK to lean on, so the
line is drawn structurally instead: `TimeoutException` from the future retries, everything else
(`ExecutionException`, i.e. whatever the `Callable` threw) fails immediately.

**Q: What does "sandboxed on a separate virtual-thread executor" actually buy you?**
Isolation of the caller from a slow or hanging tool call: `Executors.newVirtualThreadPerTaskExecutor()`
runs each invocation on its own lightweight thread, so `ToolInvoker.invoke(...)` can enforce a hard
timeout (`future.get(timeoutMs, ...)` + `future.cancel(true)`) without the caller ever blocking past
that window, and one slow tool doesn't tie up a request-handling thread. `ARCHITECTURE.md` also
mentions per-tool resource caps beyond the timeout — not built, because no concrete tool has needed
one yet and a cap picked without a real number behind it is a guess, not a design decision.

**Q: How does `ToolRegistry` find `@Tool`-annotated beans, and why `AnnotationUtils.findAnnotation`
instead of `bean.getClass().getAnnotation(Tool.class)`?**
`ApplicationContext.getBeansWithAnnotation(Tool.class)` does the discovery. Reading the annotation
back off a found bean uses Spring's `AnnotationUtils.findAnnotation` instead of a plain reflective
`getAnnotation` call because a Spring-proxied bean's runtime class (CGLIB subclass, JDK dynamic
proxy) can hide class-level annotations from a naive reflective lookup — `findAnnotation` walks
superclasses/interfaces the way Spring itself resolves annotations, so registration doesn't
silently break the moment a tool bean picks up a proxy for an unrelated reason (e.g. `@Transactional`
later).

**Q: `JitteredExponentialBackoff` used to be private methods inside `LlmClient` — why extract it now and not
back when `LlmClient` was first built?**
Because there wasn't a second user of the algorithm yet. Extracting a shared abstraction from a
single example is guessing at what's actually generic; `ToolInvoker` needing the identical
exponential-backoff-with-jitter math for timeout retries is the concrete second case that makes the
extraction a genuine simplification instead of speculative design. Same discipline that's kept
`EventConsumer` and (until today) `ToolRegistry` unbuilt — the codebase waits for a second real
need before generalizing, it just usually shows up as "don't build it yet" rather than "extract it
now."

**Q: `searchKb`'s scoring is keyword overlap, not the BM25 + pgvector hybrid search
`ARCHITECTURE.md` describes — is that a problem?**
Not for what it's proving today. The tool-registration story (discovery, timeout, retry, sandbox)
is orthogonal to how good the ranking is — swapping naive overlap for real BM25 or embeddings later
changes `SearchKbTool.search()`'s internals, not how it's registered or invoked. Building the
retrieval-quality half before there's a `RetrieverAgent` actually calling it in a pipeline would be
solving a problem nobody's hit yet.

**Q: `RetrieverAgent` looks up `searchKb` from `ToolRegistry` and casts the result to `SearchKbTool`
— isn't that a type-safety gap?**
Yes, and it's called out explicitly rather than left implicit — `ToolRegistry.get(name)` returns
`Object`, so the agent is trusting that a bean registered under the name `"searchKb"` really is a
`SearchKbTool` by convention, not by the compiler. Closing that gap properly means every tool
implementing a common, generically-invokable contract (arguments in, result out, independent of the
concrete tool class) — worth designing once there's a second tool with a different method signature
to design the contract against, not on spec for the one tool that exists today. Same "wait for the
second concrete case" reasoning as everywhere else in this codebase, applied to acknowledge a real
gap instead of pretending it isn't there.

**Q: Why does `RetrieverAgent` look up the tool from `ToolRegistry` instead of just injecting
`SearchKbTool` directly, since it's the only tool that exists?**
Because the point of a registry is dynamic dispatch by name, and direct injection would defeat that
purpose even though it would compile and work today. This is deliberately the path a future
planner-dispatched agent would use — an agent that gets told "call the tool named X" and looks it
up, rather than one that's compiled against a specific tool class. Direct injection is simpler code
for a system with one tool and one caller; registry lookup is the right shape for the system this is
built to become.

## Evals
*(not yet implemented)*

## Observability
*(not yet implemented)*

## Scaling
*(not yet implemented)*
