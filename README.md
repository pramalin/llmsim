# llmsim

**llmsim** is a small LLM simulator: a local service that answers both
OpenAI's and Anthropic's chat APIs, so you can test the business logic in
an agentic app without touching a real model, paying for real tokens, or
depending on network access.

It runs as a single instance and answers both `POST /v1/chat/completions`
(OpenAI's shape) and `POST /v1/messages` (Anthropic's shape) at the same
time, on the same port. What it replies with is decided entirely at
startup, by a script you write.

## Trying it out

With `docker compose up` running, in another terminal:

```bash
curl -s -X POST http://localhost:8089/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}]}'
```

**1. The default script.** You should get back `"This is a simulated
response."` in `choices[0].message.content`:

```json
{
  "id": "chatcmpl-sim-3f9a2b7e-2c31-4b9a-9e2a-6b7f1a2c3d4e",
  "object": "chat.completion",
  "created": 1732000000,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "This is a simulated response.",
        "tool_calls": null,
        "tool_call_id": null
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 1, "completion_tokens": 5, "total_tokens": 6 }
}
```

(`id`, `created`, and `usage` vary between runs — `usage` is a simple
word-count heuristic unless the script pins exact values, see "Usage
counts" below.)

The Anthropic-shaped endpoint answers from the same running instance, but
the request shape itself is different — `max_tokens` is required, and
each message's `content` is an array of content blocks rather than a
plain string:

```bash
curl -s -X POST http://localhost:8089/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-sonnet-5",
    "max_tokens": 100,
    "messages": [
      {"role": "user", "content": [{"type": "text", "text": "hello"}]}
    ]
  }'
```

Same reply, now in `content[0].text`, with `stop_reason: "end_turn"`:

```json
{
  "id": "msg-sim-8a1c4d2e-9f3b-4c5a-b6d7-1e2f3a4b5c6d",
  "type": "message",
  "role": "assistant",
  "content": [
    { "type": "text", "text": "This is a simulated response." }
  ],
  "model": "claude-sonnet-5",
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 1, "output_tokens": 5 }
}
```

**2. The example script.** `Default` and `WeatherFlow` are both already
built into the image, so switching is just a restart with a different
env var — no rebuild needed:

```bash
docker compose down
LLMSIM_SCRIPT=com.alai.llmsim.scripts.WeatherFlow docker compose up
```

Run the same curl command three times. You should see the reply change
each time: `"Sure, let me check the weather."`, then `"It looks like rain
today."`, then `"You're welcome!"`. Run it a fourth time and it fails
loudly instead of repeating anything — `WeatherFlow` only scripted three
calls, and that's the point: it tells you your app called it more times
than expected, instead of quietly hiding the mistake.

**3. A script you write yourself.** Create a new file:

```bash
cat > src/main/scala/com/alai/llmsim/scripts/HelloWorld.scala << 'EOF'
package com.alai.llmsim.scripts

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

object HelloWorld extends ScriptSource {
  val script: Script = Script.exactly(
    reply("Hello, world!")
  )
}
EOF
```

Since this is a brand new file, this time it does need a rebuild:

```bash
docker compose down
LLMSIM_SCRIPT=com.alai.llmsim.scripts.HelloWorld docker compose up --build
```

Run the curl command once — you get `"Hello, world!"` back. Run it again
and it fails loudly, because that script only has one step. That's the
whole exercise: a script is a Scala object with a list of things to say,
in order.

**4. A tool-call round trip.** `ToolCallFlow` is also already built into
the image:

```bash
docker compose down
LLMSIM_SCRIPT=com.alai.llmsim.scripts.ToolCallFlow docker compose up
```

The first call gets back a tool request instead of text:

```bash
curl -s -X POST http://localhost:8089/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"what is the weather in San Francisco?"}]}'
```

Look at `choices[0].message.tool_calls` — you should see a call to
`get_weather` with `arguments: "{\"city\":\"San Francisco\"}"`, and
`finish_reason: "tool_calls"`:

```json
{
  "id": "chatcmpl-sim-c1d2e3f4-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "object": "chat.completion",
  "created": 1732000010,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call-1",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\":\"San Francisco\"}"
            }
          }
        ],
        "tool_call_id": null
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10 }
}
```

Now send the follow-up call an app would
send, carrying a (made-up, for this example) tool result:

```bash
curl -s -X POST http://localhost:8089/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "what is the weather in San Francisco?"},
      {"role": "assistant", "tool_calls": [
        {"id": "call-1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"San Francisco\"}"}}
      ]},
      {"role": "tool", "tool_call_id": "call-1", "content": "68F and foggy"}
    ]
  }'
```

`choices[0].message.content` should now say `"Here's what the tool
reported: 68F and foggy"` — built from the tool result *you* put in that
request, not a fixed string:

```json
{
  "id": "chatcmpl-sim-a7b8c9d0-1e2f-3a4b-5c6d-7e8f9a0b1c2d",
  "object": "chat.completion",
  "created": 1732000011,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Here's what the tool reported: 68F and foggy",
        "tool_calls": null,
        "tool_call_id": null
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 8, "completion_tokens": 7, "total_tokens": 15 }
}
```

That's `replyFromToolResult` at work: llmsim
never called `get_weather` itself, it just read the value back out of
your request the same way it would from a real app's real tool call.

## Writing a script

That's really all there is to it: a `Script` is an ordered list of
replies (or errors), and each call your app makes gets the next one.

```scala
object MyFlow extends ScriptSource {
  val script: Script = Script.exactly(
    reply("first answer"),
    reply("second answer"),
    error(429, "simulated rate limit")   // a step can also be an error
  )
}
```

Save it under `src/main/scala/com/alai/llmsim/scripts/`, point `LLMSIM_SCRIPT` at
its fully-qualified name, and restart (rebuild only if the file is new;
editing an existing script file just needs `sbt run` again, or
`docker compose up --build`).

If you'd rather it not fail once the list runs out, swap `exactly` for
`repeatingLast` (keeps giving the last reply forever) or `cycling` (loops
back to the first one).

### Tool calls

A step can also be `toolCall(id, name, arguments)` — the model requests a
tool instead of replying with text:

```scala
Script.exactly(
  toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"San Francisco"}"""),
  replyFromToolResult("call-1")(result => s"Here's what the tool reported: $result")
)
```

There's nothing else to it — the app's follow-up request (carrying the
tool result) is just the *next* call, answered by the next step, exactly
like any other call.

`arguments` is a plain string, matching OpenAI's actual wire format
exactly (their `function.arguments` field really is a JSON-encoded
string, not a nested object) — so it's never validated, which means a
script can deliberately use a malformed string there to test how your app
handles a model that emitted broken structured output. Anthropic's
`tool_use.input` is a real nested JSON object at the wire level though,
not a string, so that scenario only works against the OpenAI-shaped
endpoint — a `toolCall` step with unparseable `arguments` fails loudly
(rather than silently coercing something misleading) if the request comes
in through the Anthropic-shaped endpoint instead.

`replyFromToolResult(toolCallId)(render)` builds its reply from the REAL
tool result your app sends back, instead of a fixed string — llmsim never
calls any tool itself, it just reads the value your app already put in
its own request (from a real function, or the app's own MCP client),
exactly the way it would hand that value to a real LLM. If no tool_result
matching `toolCallId` shows up in the request, it fails loudly rather than
guessing.

### Usage counts

Every `reply`, `toolCall`, and `replyFromToolResult` step reports token
usage by default from a simple word-count heuristic — not a real
tokenizer, just enough that `usage` in the response has *something*
plausible in it. Pin exact counts instead when a test needs to hit a
specific token-budget boundary precisely:

```scala
reply("hello", usage = usage(promptTokens = 3900, completionTokens = 50))
```

The same `promptTokens`/`completionTokens` pair maps onto both wire
shapes: OpenAI's `usage.prompt_tokens`/`usage.completion_tokens` and
Anthropic's `usage.input_tokens`/`usage.output_tokens`. Both values must
be `>= 0` — `usage(-1, 50)` throws immediately when the script is built,
rather than a negative count silently reaching either wire format.

### Response headers

Any step can also carry raw HTTP response headers, returned verbatim —
most commonly rate-limit headers or `retry-after` on an `error(...)`
step:

```scala
Script.exactly(
  reply("first answer", headers = Map(
    "x-ratelimit-remaining-requests" -> "2",
    "x-ratelimit-reset-requests"     -> "10s"
  )),
  error(429, "rate limit exceeded", headers = Map(
    "retry-after"                    -> "5",
    "x-ratelimit-remaining-requests" -> "0"
  ))
)
```

Deliberately raw strings, not a structured rate-limit type: OpenAI's
reset values are its own compact duration format (`"6m0s"`) and
Anthropic's are RFC 3339 timestamps — two genuinely different wire
formats, so the script controls the exact value that goes out rather
than llmsim deciding how to translate between them.

### Streaming (SSE)

No script changes needed for this at all — the same `reply`/`toolCall`/
`replyFromToolResult` steps answer both transports. What decides
streaming is the *request*: set `"stream": true` and llmsim answers as
Server-Sent Events instead of one JSON body.

```bash
curl -s -X POST http://localhost:8089/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"hello"}]}'
```

Against a script whose current step is `reply("Hi there")`, that's the
actual raw response body:

```
data: {"id":"chatcmpl-sim-3f9a2b7e-...","object":"chat.completion.chunk","created":1732000000,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":null},"finish_reason":null}]}

data: {"id":"chatcmpl-sim-3f9a2b7e-...","object":"chat.completion.chunk","created":1732000000,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":null,"content":"Hi","tool_calls":null},"finish_reason":null}]}

data: {"id":"chatcmpl-sim-3f9a2b7e-...","object":"chat.completion.chunk","created":1732000000,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":null,"content":" there","tool_calls":null},"finish_reason":null}]}

data: {"id":"chatcmpl-sim-3f9a2b7e-...","object":"chat.completion.chunk","created":1732000000,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":null,"content":null,"tool_calls":null},"finish_reason":"stop"}]}

data: [DONE]

```

(`id` and `created` vary between runs.) The Anthropic-shaped endpoint
answers with its own named-event format for the same script step:

```
event: message_start
data: {"type":"message_start","message":{"id":"msg-sim-...","type":"message","role":"assistant","content":[],"model":"claude-sonnet-5","stop_reason":null,"usage":{"input_tokens":1,"output_tokens":0}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"","id":null,"name":null,"input":null,"tool_use_id":null,"content":null}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":null,"output_tokens":2}}

event: message_stop
data: {"type":"message_stop"}

```

A `toolCall(...)` step streams the same way — one chunk carrying the
role, one carrying the complete tool call (name and arguments together,
not split across chunks), one final chunk with `finish_reason:
"tool_calls"` — rather than a real model's occasional habit of splitting
a single word or a tool call's arguments across several chunks. That
finer-grained, deliberately-odd chunking is future fault-injection work
(see the Roadmap), something a script will opt into, not today's default
streaming behavior.

Two things streaming doesn't do yet, on purpose, not by oversight:
scripted `usage` isn't included in a streamed response (real OpenAI only
adds it when a request sets `stream_options: {"include_usage": true}`,
which llmsim doesn't read yet), and there's no artificial delay between
chunks — every chunk sends as fast as the underlying stream can flush,
so time-to-first-token/fault-injection scenarios aren't representable
yet either. Scripted `headers` still apply exactly as they do
non-streaming.

## Inspecting captured calls

Every call the simulator receives is recorded — provider, a normalized
model/messages view (so you don't need to know both vendors' JSON shapes
just to check what was asked), the raw request body, what it was answered
with, and when. Your test harness (never the app under test) reads this
back afterward:

```bash
curl -s http://localhost:8089/_llmsim/calls
```

```json
[
  {
    "sequence": 1,
    "provider": "openai",
    "model": "gpt-4o-mini",
    "messages": [ { "role": "user", "content": "hello" } ],
    "rawRequest": { "model": "gpt-4o-mini", "messages": [ ... ] },
    "outcome": { "type": "responded", "status": 200, "body": { ... } },
    "stepIndex": 0,
    "receivedAtEpochMillis": 1732000000000,
    "completedAtEpochMillis": 1732000000012,
    "durationMillis": 12,
    "responseHeaders": [
      { "name": "x-ratelimit-remaining-requests", "value": "59" }
    ],
    "streamed": false
  }
]
```

`receivedAtEpochMillis`/`completedAtEpochMillis` are real (wall-clock)
timestamps, captured at the very top of the route before the request
body is even decoded and just before the response is returned.
`durationMillis` comes from a separate monotonic clock reading, not from
subtracting the two epoch timestamps — wall-clock time can jump (NTP
adjustment, clock skew) and is the wrong source for measuring elapsed
duration. `responseHeaders` is empty unless the step that answered this
call had `headers` set (see "Response headers" above) — a `Vector`
rather than the script's `Map`, so duplicate names, original casing, and
original order all survive into the journal even though the public DSL
only accepts a `Map` for convenience. `streamed` is `true` for a call
answered as SSE (see "Streaming (SSE)" above) — `outcome.body` records
the same logical response shape either way, so `streamed` is the only
field that tells you which transport was actually used.

`outcome.type` is one of:
- `"responded"` — answered normally; `body` is the exact response sent.
- `"rejected"` — answered with a deliberate error, either an `error(...)`
  script step or the script running out (`Overrun.Fail`).
- `"failed"` — the request body couldn't be decoded at all; no script
  step was consumed, so `stepIndex` is `null`.

Other endpoints:

- `GET /_llmsim/calls/{sequence}` — a single call by its sequence number
  (404 if it doesn't exist).
- `GET /_llmsim/status` — a quick call count.
- `DELETE /_llmsim/calls` — clears the journal only; the script keeps
  going from wherever it was. Also resets sequence numbering back to 1 —
  the next call recorded after this gets `sequence: 1` again, not a
  continuation of the old numbering.
- `POST /_llmsim/reset` — clears the journal (with the same sequence reset
  as above) *and* rewinds the script back to its first step, so a test
  suite can reuse one running simulator across many test cases instead of
  restarting the container each time.

The journal is bounded (1000 entries by default, oldest dropped first) so
a long-running simulator can't grow it without limit — override with
`LLMSIM_JOURNAL_MAX_ENTRIES`, which must be a positive integer or the
simulator refuses to start.

These all live under `/_llmsim/...`, separate from the simulated vendor
paths under `/v1/...` — the application under test only ever sees the
latter.

## Using llmsim in an app's end-to-end tests

Everything above is standalone: curl against a running llmsim, no other
service involved. Once that's working the way you expect, wiring it into
a real application is mostly just pointing that application's model
client at llmsim instead of the real API — the same way people point
Spring AI at Ollama, LM Studio, or Groq today.

**Spring AI**, for example, exposes the base URL and API key as ordinary
configuration properties, for both vendor shapes:

```properties
# OpenAI-shaped
spring.ai.openai.base-url=http://llmsim:8089/v1
spring.ai.openai.api-key=unused

# Anthropic-shaped
spring.ai.anthropic.base-url=http://llmsim:8089
spring.ai.anthropic.api-key=unused
```

(Anthropic's `base-url` doesn't need a `/v1` suffix — Spring AI always
appends `/v1/messages` itself, which is exactly llmsim's path. OpenAI's
does need it, since Spring AI appends `/chat/completions` to whatever
base URL you give it.) The API key value is never checked by llmsim, but
most clients still require *something* non-empty to be configured.

**Getting llmsim's engine into your project without copying its source.**
Every tagged release publishes two images to GHCR: `ghcr.io/pramalin/llmsim:<version>`
(a ready-to-run standalone image, bundled example scripts, for exactly the
`docker compose up` walkthrough above) and `ghcr.io/pramalin/llmsim-build:<version>`
— a reusable build environment: llmsim's source API, its resolved
dependencies, and a warm compiled build cache, meant to be used as a
build-time dependency (see below) — that's why you still run `sbt assembly`
against it rather than it being something already fully built. Your own
project's script never needs to live inside llmsim's repo, and llmsim's
engine source never needs to be copied into yours.

**Pin a released version** (`llmsim-build:0.2.0`, as below) in application
repositories — an unrelated llmsim release shouldn't be able to break your
build out from under you. `:latest` exists for quickly evaluating llmsim
itself, not for building on top of.

Your project gets its own tiny `Dockerfile` (e.g. `llmsim/Dockerfile` in
your repo) that layers just your script on top of the published engine:

```dockerfile
FROM ghcr.io/pramalin/llmsim-build:0.2.0 AS build
COPY AnalyticsFlow.scala /build/src/main/scala/com/example/agenticanalytics/llmsim/AnalyticsFlow.scala
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
COPY --from=build /build/target/scala-3.3.3/llmsim.jar /app/llmsim.jar
ENV LLMSIM_SCRIPT=com.example.agenticanalytics.llmsim.AnalyticsFlow
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/llmsim.jar"]
```

Use *your own* package for the script (`com.example.agenticanalytics.llmsim`
above, not `com.alai.llmsim.scripts`) — nothing about it needs to live
under llmsim's own namespace, it just needs to be a `ScriptSource` object
somewhere on the classpath. `sbt assembly` here is fast: the base image
already has llmsim's own engine compiled and all dependencies resolved,
so this build only compiles the one new file you added.

**In docker-compose**, llmsim is just another sibling service, built from
that small Dockerfile, reached by the other container over the compose
network's internal DNS — not `localhost`:

```yaml
services:
  llmsim:
    build: ./llmsim   # the Dockerfile above, in your own repo

  backend:
    environment:
      - SPRING_AI_OPENAI_BASE_URL=http://llmsim:8089/v1
      - SPRING_AI_OPENAI_API_KEY=unused
    depends_on:
      - llmsim
```

**The tools stay exactly as they are.** If your app's tools query a real
database or call out over its own MCP client, none of that changes —
llmsim only ever tells the app *which* tool to call and with what
arguments (deterministically, from the script); the app executes it for
real and sends the real result back, and `replyFromToolResult` uses that
value to build llmsim's reply. `AnalyticsFlow.scala` — the file copied
into the Dockerfile above — might look like:

```scala
package com.example.agenticanalytics.llmsim

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

object AnalyticsFlow extends ScriptSource {
  val script: Script = Script.exactly(
    toolCall(
      id = "call-1",
      name = "query_data_mart",
      arguments = """{"sql":"select count(*) from employee"}"""
    ),
    replyFromToolResult("call-1")(result => s"There are $result employees.")
  )
}
```

**In the test itself**, assert on the app's HTTP response as usual, and
use the call journal to confirm the app actually called the right tool
with the right arguments along the way:

```bash
curl -s http://llmsim:8089/_llmsim/calls
```

— checking `provider`, `model`, and `messages` (or `rawRequest`, for the
exact bytes) against what you expected the agent to send, without
needing any assertion logic inside llmsim itself.

## Layout

```
src/main/scala/com/alai/llmsim/
  Protocol.scala        -- case classes for OpenAI ChatRequest/Response and
                            Anthropic MessagesRequest/Response, plus their
                            error-response shapes, with circe codecs inline
  Script.scala           -- the DSL: Step, Overrun, Script, ScriptSource,
                            UsageOverride
  ScriptRunner.scala     -- advances through a Script's steps, one per call
  CallJournal.scala      -- records every call for later inspection
  Simulator.scala        -- http4s routes: /v1/chat/completions, /v1/messages
  ManagementRoutes.scala -- test-harness routes: /_llmsim/calls, /status, /reset
  App.scala              -- combines both route sets into one HttpApp
  Main.scala             -- loads a script by name (LLMSIM_SCRIPT) and serves it
  scripts/
    Default.scala        -- the built-in fallback script
    WeatherFlow.scala    -- an example fixed multi-call sequence
    ToolCallFlow.scala   -- an example tool-call round trip
    VerificationFlow.scala -- what ci/spring-verification runs against

src/test/scala/com/alai/llmsim/
  SimulatorSpec.scala           -- in-process tests of the simulator itself
  PublishedApiContractSpec.scala -- checks our case classes decode example
                                     payloads shaped like each vendor's own
                                     published API docs (no network, no keys)

ci/
  smoke-test/            -- a tiny fixture project proving the documented
                             Pattern A extension mechanism (build FROM
                             llmsim-build, add one script, sbt assembly)
                             actually still works against a given image
  spring-verification/   -- a minimal Spring Boot + Maven module proving a
                             real Spring AI client correctly parses what
                             llmsim sends -- llmsim's own tests can only
                             assert what it sent, not that a real client
                             parsed it. Includes a real, registered
                             Java @Tool (WeatherTool) for a full
                             non-streaming tool-callback round trip, not
                             just checking that a tool_calls block was
                             parsed. Both are release gates; see
                             "Releasing new versions" below, and
                             "Running ci/spring-verification locally"
                             under "Testing" to run it yourself.
```

## Running locally

```
sbt run                                           # boots with scripts/Default
LLMSIM_SCRIPT=com.alai.llmsim.scripts.WeatherFlow sbt run  # boots with a different script
LLMSIM_PORT=9000 sbt run                          # listens on a different port (default 8089)
```

## Running with Docker

```
docker compose up --build
```

builds the simulator inside the image and serves it on `localhost:8089` —
no local Scala or sbt needed. To boot with a different script:

```
LLMSIM_SCRIPT=com.alai.llmsim.scripts.WeatherFlow docker compose up
```

To use a different port, `LLMSIM_PORT` controls both the container's
listening port and the host-side mapping in `compose.yaml`:

```
LLMSIM_PORT=9000 docker compose up
```

(only add `--build` if the script is a new file that isn't in the image yet).

## Releasing new versions

Pushing a version tag publishes both images to GHCR automatically (see
`.github/workflows/publish.yml`):

```bash
git tag v0.2.1
git push origin v0.2.1
```

> **A note on the version history**: `v0.2.0` and `v0.1.1` were tagged out
> of semantic order — `v0.1.1` was pushed *after* `v0.2.0`, so it
> currently holds the floating `:latest` tag despite the lower version
> number. `v0.1.1`'s commit is a strict, linear descendant of `v0.2.0`'s
> (nothing is missing, `v0.1.1` simply has six more commits on top) — it
> was a numbering mistake, not a content divergence. Every release from
> `v0.2.1` onward is numbered to stay strictly increasing with push order
> going forward.

This runs the full test suite, then gates the push on two independent
checks against the images about to ship, not just against llmsim's own
source:

- **`ci/smoke-test/`** — a tiny fixture built from `llmsim-build`, proving
  the documented Pattern A extension mechanism (layer one script on top,
  `sbt assembly`) still works against this exact image.
- **`ci/spring-verification/`** — the standalone image, booted with the
  bundled `VerificationFlow` script, checked against a real Spring AI
  client (`mvn test`) rather than llmsim's own assertions about what it
  sent. This is the check that a real consumer can actually parse
  llmsim's responses, not just that llmsim believes they're well-formed.

Only if the tests and both gates pass does anything get pushed:
`llmsim-build:<version>` and `llmsim:<version>` (the standalone image),
each also tagged `:latest`.

## Testing

```
sbt test
```

Everything runs with no network access and no API keys — `SimulatorSpec`
exercises the routes in-process, and `PublishedApiContractSpec` checks our
case classes against example payloads shaped like each vendor's published
docs rather than making a live call.

### Running ci/spring-verification locally

`sbt test` never makes a real Spring AI call, so it can't tell you
whether a real client actually parses what llmsim sends — that's what
`ci/spring-verification` is for (see "Layout" above). It needs a running
llmsim instance, booted with the specific script its tests expect:

```bash
# terminal 1 -- boot llmsim with the script ci/spring-verification runs against
LLMSIM_SCRIPT=com.alai.llmsim.scripts.VerificationFlow sbt run
```

Wait for the boot log to say `booting with script
'com.alai.llmsim.scripts.VerificationFlow'` — if it says `Default`
instead, the `LLMSIM_SCRIPT` environment variable didn't take effect and
every test will fail against the wrong script.

```bash
# terminal 2, once llmsim is up
cd ci/spring-verification
mvn test
```

If you've changed a `.java` file and Maven says `Nothing to compile - all
classes are up to date` without picking it up, run `mvn clean test`
instead — Maven's staleness check has been wrong about this before.

Every push to `main` and every pull request also runs the full gate
(`.github/workflows/ci.yml`): the test suite, the `ci/smoke-test/`
extension-pattern check, and `ci/spring-verification`'s real Spring AI
client checks — the same three checks `publish.yml` runs before a
release, just without a version tag or anything getting pushed. Ordinary
commits weren't tested at all before this existed; only a version-tag
push ran anything.

## Roadmap

1. ~~Single canned response~~ — done
2. ~~Scriptable responses~~ — done
3. ~~Captured-call journal~~ — done (`/_llmsim/calls`, `/_llmsim/status`, `/_llmsim/reset`)
4. ~~Tool-call round trips~~ — done (`toolCall`, and `replyFromToolResult` to build a reply from the app's real tool result rather than a fixed string)
5. ~~Published, reusable distribution~~ — done (`ghcr.io/pramalin/llmsim-build` as a dependency for consuming projects, `ghcr.io/pramalin/llmsim` standalone)
6. ~~Duration and correlation fields on `CapturedCall`~~ — done: `receivedAtEpochMillis`/`completedAtEpochMillis`, with a monotonic-clock-derived `durationMillis`. Concurrent test isolation is handled outside llmsim (an ephemeral instance per test class, or `/_llmsim/reset` for a shared long-lived one) rather than with an in-process session concept — an earlier draft of this item planned a custom `X-LLMSIM-SESSION` header for that, which was deliberately dropped in favor of not pushing an llmsim-specific concept into application/test code.
7. ~~Scriptable usage counts and response headers~~ — done: `usage(promptTokens, completionTokens)` on any step for pinning exact token counts, and raw `headers` on any step for rate-limit headers, `retry-after`, or anything else — see "Writing a script" above.
8. ~~Plan and scope a sample Spring AI verification module~~ — done, as `ci/spring-verification/`.
9. ~~Build it~~ — done: a minimal Spring Boot + Maven module (`ci/spring-verification/`) proving a real Spring AI client (both OpenAI- and Anthropic-shaped) correctly parses what llmsim sends, including a `@Disabled` test tracking [spring-projects/spring-ai#6607](https://github.com/spring-projects/spring-ai/issues/6607) (OpenAI rate-limit headers aren't wired into `ChatResponseMetadata` as of Spring AI 2.0.0 — an upstream gap, not an llmsim one). Wired into `.github/workflows/publish.yml` as a release gate alongside the existing smoke test.
10. ~~Cut a release~~ — done, `v0.1.1`: items 6–9 above, a real usable release on its own, none of it depending on SSE existing.
11. ~~Small pre-SSE prep batch~~ — done: `CapturedCall.responseHeaders` (a `Vector[CapturedHeader]`, not a `Map`, so duplicate names/casing/order survive into the journal even though the script DSL still accepts a `Map`); array-of-parts message content now joined with a space instead of concatenated with none; `UsageOverride` rejects negative values at construction; and `.github/workflows/ci.yml`, running the same three gates as `publish.yml` on every push to `main` and every pull request, not just on a version-tag push. Also where the `v0.1.1`/`v0.2.0` tag-numbering mixup got caught and corrected — see "Releasing new versions" above.

12. ~~Streaming responses (SSE)~~ — done, for both OpenAI- and Anthropic-shaped endpoints: `Protocol.scala` gained `stream` on both request types plus each vendor's real streaming wire shapes (OpenAI's data-only `chat.completion.chunk`, Anthropic's named `message_start`..`message_stop` event sequence), `Simulator.scala` gained six guarded branches (`Reply`/`ToolCall`/`ReplyFromToolResult` × two providers) so the exact same script answers either transport, and `CapturedCall.streamed` records which one a given call used. MVP sends whole units per chunk (a whole word, a whole tool call) — see "Streaming (SSE)" above for what's deliberately deferred and why.

    **Verified two ways, per the original definition of done.** llmsim's own wire-level tests (`SimulatorSpec.scala`, "OpenAI/Anthropic SSE streaming") assert on the raw frames — that's the authoritative check on what llmsim actually sends. `ci/spring-verification` extends the same real-client principle used everywhere else in this module: `openAiShapedClientStreamsTheScriptedReply`/`anthropicShapedClientStreamsTheScriptedReply` consume a real `Flux<String>` end to end, and `openAiShapedClientSurfacesTheStreamedToolCall` confirms a streamed tool call is still there once the stream completes — proving a real Spring AI `ChatClient` parses llmsim's streaming output correctly, not just that llmsim believes it's well-formed.

    ~~Streamed tool-callback round trip~~ — done: `openAiStreamedToolCallRoundTripActuallyExecutesAndAnswers` mirrors the non-streaming baseline below but over `ChatClient.stream()` instead of `.call()` — llmsim returns a tool call across streamed chunks, Spring AI's tool-calling advisor recognizes it, invokes the real registered `@Tool`, and streams the final answer built from the follow-up request's real result. This was the one piece of this whole feature where the actual uncertainty was in Spring AI's internals rather than llmsim's own code — a materially different, less-traveled code path than the synchronous one — and it passed working exactly as the non-streaming baseline did.

    ~~Non-streaming tool-callback baseline~~ — done, ahead of the streaming work: `openAiToolCallRoundTripActuallyExecutesAndAnswers` registers a real Java `@Tool` on its own dedicated `ChatClient` (nothing global, so no other test risks auto-executing a tool call it only means to inspect), and confirms the full loop — llmsim returns a tool call, Spring AI actually invokes the callback, the real return value comes back in the follow-up request, `replyFromToolResult` answers from it. `openAiShapedClientSurfacesTheToolCall` (no tool registered) still covers the narrower "Spring AI parsed the block" case on its own. Having this pass first meant a streamed-tool-call failure would have been clearly an SSE problem, not an ambiguous one.

13. Bare-bones dashboard — a plain JSON endpoint (`GET /_llmsim/dashboard`) rendered by a single static HTML page served alongside the API, no build step or framework. Makes call outcomes and latency visible while streaming and fault injection are still being iterated on — not a final UI, and not a substitute for item 12's real-client verification.
14. Streaming fault injection: delayed first token, delayed inter-token gaps, mid-stream disconnect, malformed SSE event, stream ending without a completion event, tool-call arguments split across chunks, and HTTP 429 before streaming begins. Validated against the item 13 dashboard as each fault type is added, and extend `ci/spring-verification` (item 12) to cover the fault types that matter most for a real client (mid-stream disconnect, split tool-call arguments). This is also where `CallJournal`'s record-on-completion model is worth revisiting toward a `begin`/`complete`/`fail`/`cancel` lifecycle — deliberately not built for item 12, since a fully scripted, non-delayed stream has no genuine in-flight/cancelled state to represent yet, but delayed and disconnectable streams do.
15. `GET /v1/models`.
16. The real Angular console (`console-angular/`), served by the standalone image at `/_llmsim/ui`, with overview/calls/timeline/streaming views as designed. Deliberately last — the data model (streams, faults) needs to be settled first so the UI is built once against a stable shape instead of reworked mid-flight.

`agentic-analytics` remains a pure downstream consumer throughout all of the above — it always pins a released `llmsim-build` tag (never a dev build), the same as any other project building on llmsim.

Downstream work that depends on this roadmap — `agentic-analytics` end-to-end streaming and its own deterministic regression suite, and the Spring AI + Playwright MCP UI-testing agent — lives in those projects' own planning docs, not here, since llmsim's roadmap should only track what ships inside this repo.