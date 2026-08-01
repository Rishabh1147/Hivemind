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
credibility in an interview. As of the last devlog (2026-07-30, session 10): four agents
(`ClassifierAgent`, `RetrieverAgent`, `ResponderAgent`, `RoutingAgent`) genuinely chained through
Kafka — classify → retrieve → respond → route — with `GET /tickets/{id}` reaching a final routing
decision, both ticket state and the full event history persisted in real Postgres (`TicketRepository`,
`AuditLog`), and an eval harness (`verticals/triage/eval/`) that runs 10 hand-written cases and
gates on category accuracy / citation recall / p95 latency with a real process exit code. The full
`ARCHITECTURE.md` pipeline is real except the `Stream` step (SSE to a dashboard that doesn't exist).
Still not built: a planner that *decides* the chain dynamically (it's four hardcoded consumer→topic
links, not planner-routed), pgvector-backed KB persistence (`KnowledgeBase` is still 5 hardcoded
chunks), the eval harness's tone scoring and cost gating, any CI workflow actually invoking the
harness, OpenTelemetry, frontend. Check `docs/devlog/` for the current honest state before claiming
anything more specific.

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
the resulting chunks get published as a `TicketRetrieved` event (carrying the ticket body forward
again) to `hivemind.triage.retrieved`. This stage still doesn't touch `TicketStatusStore` —
retrieval alone isn't a result worth surfacing.

One more time, `TicketRetrievedConsumer` picks up that event. If retrieval failed it stops. Otherwise
it hands the ticket body and chunks to `ResponderAgent`, which drafts an answer via Claude (same
strict-JSON-prompt approach as the classifier). This consumer *does* write into `TicketStatusStore`
— it reads the ticket's current `TriageResponse` (still carrying `category`/`confidence` from the
classify stage) and calls `.withResponse(draft)` on it, an instance method that preserves those
fields while updating `status`/`answer`/`citedChunkIds`, rather than building a fresh record that
would lose them. It also publishes a `TicketResponded` event to `hivemind.triage.responded`.

Finally, `TicketRespondedConsumer` picks that up and hands the ticket's *whole current*
`TriageResponse` to `RoutingAgent` — unlike every earlier consumer, it does not skip on failure:
even a `response_failed` ticket gets routed (to `ESCALATE`), since that's exactly the ticket that
most needs a human to see it. `RoutingAgent` is the one agent that never calls Claude — auto-resolve
vs. queue vs. escalate is a deterministic function of category, confidence, and status. The consumer
writes the decision into `TicketStatusStore` and publishes a final `TicketRouted` event.
`GET /tickets/{id}` now reaches the pipeline's true terminal state: `routed`, with a `routingDecision`
alongside everything the earlier stages produced.

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

**Q: `ResponderAgent` uses the same strict-JSON-via-prompt approach as `ClassifierAgent` — is that
just copying the pattern, or is there a reason it still fits?**
Both: reuse where the shape genuinely repeats, and this one still has a good reason. `ResponderAgent`
extracts two fields (`answer`, `citedChunkIds`) — still simple enough that a tool-calling schema
would be more ceremony than value, the same threshold `ClassifierAgent`'s Q&A above draws. It also
deliberately handles zero retrieved chunks as a normal case, not a failure: the prompt tells Claude
to say the KB doesn't cover the ticket rather than invent policy, so a ticket with no KB match still
gets a legitimate answer instead of the agent short-circuiting before ever calling the LLM.

**Q: `TriageResponse` used to be built fresh by each factory method (`pending`, `classified`,
`failed`) — why does the respond stage use instance methods (`withResponse`,
`withResponseFailure`) instead?**
Because by the time a ticket reaches the respond stage, its `TriageResponse` needs to carry results
from *two* prior stages at once — `category`/`confidence` from classify, plus the new
`answer`/`citedChunkIds` from respond. Building a fresh record from scratch (the way `classified(...)`
does) would mean either re-passing the classification result into the respond consumer, or losing it.
Neither's right: `TicketRetrievedConsumer` reads the ticket's *current* stored `TriageResponse` and
calls `.withResponse(draft)` on it — an instance method with access to `this`, so it can carry
forward whatever's already there while only changing what this stage actually produced. Records
being immutable doesn't mean every update has to rebuild from nothing; it means each update is an
explicit, named transformation, which is more honest about what's actually changing than a
seven-argument constructor call would be.

**Q: Any real bugs or surprises building the respond stage?**
A genuine race condition in the *test*, not the app: `TriageKafkaIntegrationTest` originally polled
the HTTP status for the intermediate `"classified"` value, the same way it already polled for
`"pending"` → anything-else. With three consumers all reacting near-instantly (mocked LLM calls,
in-memory KB, no real network latency), the ticket could reach `"responded"` between two 250ms polls
— skipping straight past `"classified"`, so the test occasionally failed asserting a state that had
already come and gone. Fixed by reading the intermediate `TicketRetrieved` event directly off Kafka
(read once, not polled, so there's nothing to race) and only polling HTTP for the terminal
`"responded"` status, asserting `category`/`confidence` from that same final read rather than an
intermediate one it might miss. The lesson: polling for an exact intermediate state in a fast,
multi-stage async pipeline is inherently racy — poll for "no longer earlier than X" or for the
terminal state, not for a state that might not still be current by the time you observe it.

## Kafka / eventing

**Q: Why does the ticket endpoint return `202 Accepted` instead of `200 OK` with the classification
now?**
That's the concrete tradeoff of putting Kafka between the controller and the agent: the request
thread publishes an event and returns, it doesn't block on Claude anymore. `202` with a `pending`
status is the honest HTTP status for "accepted, not yet done" — returning `200` with a result that
doesn't exist yet would be wrong. `GET /api/v1/triage/tickets/{id}` is the interim way to observe
progress until the `Stream` step in `ARCHITECTURE.md` (SSE to a dashboard) exists — swapping that in
later only changes the read side, not the event-bus wiring.

**Q: Why publish events a stage ahead of having a consumer for them — isn't
`hivemind.triage.responded` doing that right now with no consumer at all?**
Yes, and it's the fourth time this pattern has repeated (`classified` and `retrieved` were both
published before anything consumed them; both are consumed now). The event log is meant to be the
audit trail (`ARCHITECTURE.md`: "the event log *is* the audit log"), and publishing a topic's
contract alongside its producer — rather than designing it later from whatever the eventual consumer
turns out to need — is the consistent choice across every stage in this vertical. The natural future
consumer for `hivemind.triage.responded` is a Router/planner stage that isn't built yet.

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

**Q: Walk me through the `EventConsumer<T>` base class — why did it take until the third consumer to
build it, and what does it actually do?**
Through 2026-07-17 there was one consumer, and extracting a shared shape from a single example would
have been guessing at what varies — the same reasoning that kept `ToolRegistry` unbuilt until there
was a second tool. By 2026-07-21 there were two with an identical shape, which made the extraction
no longer speculative, but it still wasn't done — that session's focus was making the second
consumer exist for real, not generalizing as a side effect of adding it. On 2026-07-30, with a third
consumer about to be written, the extraction finally happened: `EventConsumer<T>` takes an
`ObjectMapper` and the event `Class<T>` in its constructor, exposes a `protected final void
consume(String rawJson)` that deserializes (logging and dropping anything unparseable) and calls the
subclass's abstract `onEvent(T)` — itself wrapped in a try/catch that logs rather than lets an
unhandled exception reach the Kafka listener container thread. `ClassifyRequestConsumer` and
`TicketClassifiedConsumer` were refactored onto it first, with the full test suite re-run green
before adding anything new on top — verify the refactor is safe in isolation before building a
feature on it. `TicketRetrievedConsumer` was then written directly against the base rather than
copy-pasted and cleaned up afterward.

**Q: How does a Kafka listener fail without silently dropping messages forever?**
Two layers, both now centralized in `EventConsumer<T>` rather than duplicated per consumer: a
message that can't be deserialized is logged and dropped (fails loudly in logs, not the request),
and any exception `onEvent` throws is caught and logged rather than reaching the listener container
thread. In practice the second layer rarely fires for the triage agents specifically, since
`ClassifierAgent`/`RetrieverAgent`/`ResponderAgent.handle()` already can't throw — every failure
path returns `AgentResult.failure(...)`, which becomes a `*_failed` status instead of an uncaught
exception. That's deliberate reuse of failure handling already built and tested (see 2026-07-15/16
devlogs) rather than duplicating it at the Kafka layer; `EventConsumer`'s catch is a safety net for
anything outside the agent call (e.g. the status-store write), not the primary mechanism. A real
dead-letter-topic + retry policy is still future work.

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

**Q: `TicketRetrieved` carries `ticketBody` from the day it was created, unlike `TicketClassified`
which had it added after the fact — why the difference?**
Because the lesson from `TicketClassified` (split out of `TriageResponse` specifically because it
didn't carry a field `RetrieverAgent` turned out to need) generalizes: any event a Kafka consumer
publishes for a *next* stage should carry what that next stage will plausibly need, not just what
the current stage happens to produce. `ResponderAgent` needs the ticket body for the same reason
`RetrieverAgent` did — grounding its output in the actual ticket, not just derived data — so
`TicketRetrieved` was given `ticketBody` up front on 2026-07-30 instead of shipping without it and
discovering the same gap a second time.

**Q: How was the respond stage verified without a real Anthropic API key, same as the retrieve
stage?**
Identical isolation technique, one stage further: published a hand-built `TicketRetrieved` JSON
straight onto `hivemind.triage.retrieved` with `kafka-console-producer.sh` (`status: "retrieved"`,
one KB chunk, a real ticket body), which triggered `TicketRetrievedConsumer` → `ResponderAgent` →
a real call to the real Anthropic API with a deliberately invalid key. The fast-failed
`response_failed` result showed up correctly on `hivemind.triage.responded`, and — the part that
actually proves the new behavior — `GET /api/v1/triage/tickets/manual-test-3` returned the identical
status and error, confirming `TicketRetrievedConsumer` genuinely wrote into `TicketStatusStore` and
not just the topic. Same pattern used for every stage so far: isolate the new consumer by hand-crafting
its input event, and let the one thing that's genuinely external (Claude) fail for a real, expected
reason rather than mocking it away entirely.

**Q: Why is `RoutingAgent` deterministic instead of another Claude call, given every other agent
uses an LLM?**
Because the decision it makes — auto-resolve, queue for a human, or escalate — is a pure function of
two things already known by this point in the pipeline: category and confidence (plus whether the
respond stage succeeded). Routing that through Claude would add latency, cost, and non-determinism
to a decision that doesn't need any of them, purely to keep a "every agent calls an LLM" pattern
consistent for its own sake. `BaseAgent<T>`'s contract (`handle(AgentContext) -> AgentResult<T>`)
doesn't care whether the implementation calls Claude or just evaluates an if/else chain — the
abstraction was never "agent = LLM call," it was "agent = a step in the pipeline with a typed
result." `RoutingAgentTest` reflects this: no mocks at all, since there's no external dependency to
isolate from.

**Q: Why does `TicketRespondedConsumer` break the skip-on-upstream-failure pattern every other
consumer follows?**
Because "skip" means something different depending on what's missing. `TicketClassifiedConsumer`
skips on `classification_failed` because there's no category to search a knowledge base against —
skipping is correct, there's nothing useful to do. Same for `TicketRetrievedConsumer` skipping on
`retrieval_failed`. But a `response_failed` ticket isn't missing an input the next stage needs — the
next stage's whole job *is* deciding what happens to a ticket, and "this ticket had a failure
somewhere upstream" is itself a valid, important input to that decision. Skipping would leave the
ticket with no routing decision at all, silently stuck. Escalating it is the correct behavior, not
an exception to the skip pattern — the pattern was never "always skip on upstream failure," it's
"do whatever's correct for this specific transition," which for the last stage means never leaving a
ticket un-routed.

**Q: How was the route stage verified without a real Anthropic API key?**
It didn't even need a new hand-built message. `RoutingAgent`'s decision depends on the ticket's
*current* `TriageResponse` read from `TicketStatusStore`, not on the incoming event's own fields —
so republishing the same kind of hand-built `TicketRetrieved` message already used to verify the
respond stage cascaded through **both** new consumers in one shot: real Anthropic auth failure →
`response_failed` written to the store → `TicketRespondedConsumer` reads that status →
`RoutingAgent` correctly escalates → `TicketRouted{routingDecision: ESCALATE}` on the topic, matched
by `GET /tickets/{id}`. `AUTO_RESOLVE`/`QUEUE_FOR_HUMAN` aren't reachable this way without a real key
(the store never holds a real category/confidence pair without a successful classify call) — those
branches are covered directly by `RoutingAgentTest` instead, which controls the `AgentContext`
itself and has no store dependency. Two different verification layers covering what each can
actually reach, not one technique stretched to do both jobs.

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

## Persistence

**Q: Why `JdbcTemplate` instead of Spring Data JPA/Hibernate for the Postgres layer?**
The schema is one row per ticket (`tickets`, upserted in place) and one row per event
(`audit_events`, insert-only) — no relationships, no lazy loading, nothing an ORM's entity
lifecycle would actually help with. JPA would add real complexity (entity state management,
session/transaction semantics, N+1 query risk) for a workload that's fundamentally two SQL
statements: an upsert and an insert. `TicketRepository`/`AuditLog` are the same "thin explicit
wrapper" shape as `EventBus` (wraps `KafkaTemplate`) and `LlmClient` (wraps LangChain4j's
`ChatModel`) — one seam per external dependency, no framework magic in between.

**Q: Why two tables — `tickets` and `audit_events` — instead of one?**
Because they answer different questions and have different write patterns. `tickets` answers
"what does this ticket look like right now" — one row per ticket, overwritten in place as the
ticket moves through the pipeline (`ON CONFLICT (id) DO UPDATE`). `audit_events` answers "what
happened, in order" — append-only, one row per event, never updated or deleted. Merging them would
mean either losing history (a mutable table can't be an audit log) or losing the cheap
current-state lookup `GET /tickets/{id}` needs (deriving current state from an event log on every
read is a legitimate pattern — event sourcing — but is more machinery than this system needs today).

**Q: `EventBus.publish()` now writes to Kafka and Postgres in the same call — isn't that two
side effects from one method, which usually smells like the method is doing too much?**
It's one side effect conceptually — "publish this event" — implemented as two writes because
there isn't yet infrastructure to derive one from the other. In a fuller design, Postgres would
likely be populated by consuming from Kafka (a dedicated audit consumer), keeping `EventBus` doing
only the Kafka send. That's more machinery (another consumer, another topic subscription, another
thing that can lag) for a benefit — decoupling the two writes — that doesn't matter yet with one
producer and no throughput concerns. Direct dual-write was the simpler correct choice for where the
project actually is: it guarantees the two logs can't drift (one method, two writes, no window
where a crash leaves Kafka published but Postgres not, or the reverse split across two consumers),
at the cost of a coupling that would need revisiting if `EventBus` ever needed to scale
independently of the audit write.

**Q: How does this affect testing — did adding Postgres change every existing test?**
Yes, and not by choice: Spring Kafka's autoconfigured beans connect *lazily*, so a `@SpringBootTest`
with no real broker still starts fine. Flyway is the opposite — it connects and runs migrations
*eagerly* at context startup, so a missing database fails the whole Spring context, not just the
code that touches it. That meant three existing test classes that never cared about a database
before this session all needed a real Postgres just to start. Extracted a shared
`AbstractPostgresIntegrationTest` (`@Testcontainers` base with the container + property wiring) once
a third class needed the identical setup — the first time this codebase's "wait for a real repeated
need before extracting" discipline applied to test infrastructure instead of production code.

**Q: What actually proved the Postgres migration works, beyond "the tests pass"?**
The concrete demo, not just assertions: submitted a ticket, confirmed its row in `tickets` and its
rows in `audit_events` via direct `psql` queries (not the API), then **killed the running app
process entirely and started a fresh JVM against the same Postgres container** — `GET
/api/v1/triage/tickets/{id}` for the same ticket returned identical data. The in-memory
`TicketStatusStore` this replaced would have lost everything on that restart, silently, with no
error — durability isn't something you can verify by reading the code, only by actually taking the
process down and bringing it back up. Flyway's second-boot log (`Successfully validated 2
migrations`, no re-run) also confirmed the migration mechanism itself is idempotent, not just that
data survived.

## Evals

**Q: Why does `TriageEvalRunner` call the four agents directly instead of submitting each eval
case as a real ticket through the API and Kafka, the way the app actually runs in production?**
Because an eval run and a production request are answering different questions. A production
ticket needs replay, independent scaling, and an audit trail — Kafka earns its cost there. An eval
run needs to score model decision quality across many cases, fast and repeatably — for that, the
event bus is pure overhead: async polling latency and a source of flakiness that has nothing to do
with whether the model classified correctly. `TriageKafkaIntegrationTest` already proves the Kafka
wiring works; the eval harness's job is a different one, so it reuses the same agents (identical
business logic) through a different, more direct execution path.

**Q: `docs/EVALS.md`'s schema always includes an `expected.routing` field — why is
`TriageEvalCase.expectedRouting` nullable, and usually null, in the actual implementation?**
Because routing depends on model confidence, and confidence isn't something a human authoring a
gold-labeled ticket can know in advance — it's an output of running the classifier, not an input a
test case controls. The one case where routing *is* predictable regardless of confidence is
`ABUSE` (`RoutingAgent` escalates on category alone), so that's the only case asserting it. A null
`expectedRouting` scores as "not applicable," not "must literally be null" — the scorer skips the
check entirely rather than penalizing every non-abuse case for a property the eval author
genuinely couldn't have known.

**Q: The eval cases reuse specific word forms from the knowledge-base chunk text almost verbatim
— isn't that cheating, making the retrieval score look better than it should?**
It's testing what's actually there, honestly. `SearchKbTool`'s scorer (2026-07-20) is naive keyword
overlap with no stemming — "payment" and "payments" are different tokens to it. Writing eval-case
tickets in more naturally varied language would under-test citation recall against the *current*
implementation, producing a lower score that reflects the eval wording, not a real system
limitation. The honest thing is to write cases that exercise the system as it genuinely works today;
when `SearchKbTool` is upgraded to real BM25 or embeddings (both explicitly on the roadmap), the
same cases will still be valid — they'll just also start passing with more naturally-varied wording,
which is itself a good regression signal for that future change.

**Q: Walk me through a real bug from building this.**
`TriageEvalHarnessRunner` calls `SpringApplication.exit(applicationContext, () -> passed ? 0 : 1)`
to gate a CI-style pass/fail. First version stopped there — the log line correctly said `Eval run
FAILED gating thresholds`, but the process exit code was always `0`. `SpringApplication.exit(...)`
only *computes* an exit code and closes the Spring context; it doesn't call `System.exit(...)`
itself, so nothing actually told the JVM to terminate non-zero. A CI step checking `$?` would have
seen a failing run reported as a pass. Fixed with `System.exit(SpringApplication.exit(...))`. Found
by literally running the packaged jar (`java -jar target/hivemind-*.jar
--spring.profiles.active=eval`) and checking `$?` directly rather than trusting that a correct-looking
log line meant the mechanism worked — the log output was byte-for-byte identical before and after
the fix, so reading logs alone would never have caught it.

**Q: How was the harness verified to actually gate correctly, without a real Anthropic key to
produce a passing run?**
By confirming it fails *correctly* — which is itself real, useful behavior to verify. Ran it with a
deliberately invalid key: every case genuinely errors at the classify step (a real Anthropic auth
error per case, not a stub), `categoryAccuracy` computes to exactly `0.0`, the run logs as failing
gating, and the process exits `1`. That's precisely what should happen to a broken pipeline, and
precisely the behavior a real CI gate depends on. A harness that can't be shown to fail correctly
isn't trustworthy even if it might pass correctly; this is the same reasoning behind checking
`ToolInvoker`'s and `LlmClient`'s failure paths as carefully as their success paths.

## CI / delivery

**Q: The eval harness has been ready to gate CI since session 10 — why doesn't the GitHub Actions
workflow (session 11) actually run it?**
Cost and secrets, weighed honestly rather than defaulted past. Running the eval harness means real
Claude API calls — a live `ANTHROPIC_API_KEY` would have to sit in GitHub Actions secrets, and every
push/PR would burn real money, for a project still at 10 of the 50+ target eval cases. `./mvnw test`
(35 tests) needs neither: Testcontainers spins up its own Postgres and Kafka per test class, so the
whole suite is self-contained and free to run on every push. The eval harness stays a deliberate,
local/manual gate for now — the honest state is "CI-gated on tests, not yet on evals," not "CI-gated"
unqualified. Revisit once the eval set and the interview story around cost justify the spend.

**Q: Why is `./mvnw test` alone sufficient as the whole CI command — no separate integration-test
phase?**
There's no Failsafe plugin in `pom.xml`, so Surefire's default `**/*Test.java` include pattern
already picks up every test class, unit and Testcontainers-integration alike
(`TriageKafkaIntegrationTest`, `HivemindApplicationTests`). One Maven phase, one command. GitHub's
`ubuntu-latest` runners ship Docker pre-installed, so Testcontainers needs no extra CI setup either —
verified by running the exact `./mvnw -B test` command locally before trusting it on a runner.

## Observability

**Q: How does one trace id follow a ticket across four independent, asynchronous Kafka consumers
without the trace id ever being part of the event payload itself?**
Through Kafka message headers, not the JSON body. `EventBus.publish()` is the one place every
vertical sends a message through (never `KafkaTemplate` directly), so it's the one place trace
context needs injecting: it opens a span as a child of whatever span is current on the calling
thread, then uses Micrometer Tracing's `Propagator.inject(...)` to write that span's context into
the `ProducerRecord`'s headers. `EventConsumer.consume()` — the shared base every `@KafkaListener`
extends — is the mirror image: it reads those headers back out with `Propagator.extract(...)` and
runs the agent inside a span that's a child of whatever it just extracted. Because publish always
asks "what span is current," and every consumer's `onEvent` publishes the next stage's event from
*inside* the span it just extracted, the chain nests automatically: HTTP request span → publish
`classify` → consume `classify` → publish `classified` → consume `classified` → ... No code anywhere
passes a trace id as data; the propagation is entirely a side effect of the two shared seams
(`EventBus`, `EventConsumer`) every stage already went through for other reasons.

**Q: Why the logging exporter instead of standing up Jaeger or an OTLP collector?**
Same "don't build the second thing until the first thing needs it" discipline used everywhere else
in this codebase. A `LoggingSpanExporter` bean is the entire wiring cost — one `@Bean` method — and
it's enough to prove the mechanism works: grep the logs for a trace id and see every span that shares
it. A real backend is a one-bean swap later (add an OTLP exporter, point it at a collector), not a
rewrite; standing up Jaeger now to demo something a log grep already proves would be infrastructure
for its own sake.

**Q: How was propagation actually verified, versus just "the code compiles and looks right"?**
Real infra, real Kafka hop, real log output — not a unit test with a mocked `Propagator`. Started
`docker-compose` (Kafka + Postgres), ran the app with a deliberately invalid Anthropic key (the same
constraint every session's manual verification has had — no working key available), POSTed one
ticket, and grepped the `LoggingSpanExporter` output. Five spans came back sharing one trace id:
the HTTP `POST` span (autoconfigured, no code written for it), `kafka.publish classify`,
`kafka.consume ClassifyRequested`, `kafka.publish classified`, `kafka.consume TicketClassified` — the
chain stopping there because classification genuinely failed on the bad key and
`TicketClassifiedConsumer` correctly skips retrieval for a failed classification, same behavior every
prior session's dummy-key run has shown. The propagation mechanism is identical at every hop, so
proving it for real at the first two is strong evidence for the rest, but a full trace through
retrieve/respond/route still needs a real key to actually witness end to end.

**Q: None of the spans in that verification run carry an error tag, even though classification
failed. Is that a bug in the tracing code?**
No — it's `EventConsumer`'s span only calling `span.error(e)` inside its `catch (Exception e)` block,
and `ClassifierAgent`'s failure never throwing. Failure is data here (`AgentResult.failure(...)`),
per this codebase's standing rule that anything crossing a boundary (an HTTP controller, a Kafka
listener) surfaces failure as a return value, not an exception — so the span correctly reports
"nothing exceptional happened," even though the *business outcome* was a failure. If a real bug ever
made an agent throw instead of returning a typed failure, that would show up as a red-flagged span —
which is itself a useful, if accidental, property of keeping the two concepts (thrown exceptions vs.
domain failures) distinct.

## Scaling
*(not yet implemented)*
