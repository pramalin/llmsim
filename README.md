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
response."` in `choices[0].message.content`.

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

Same reply, now in `content[0].text`, with `stop_reason: "end_turn"`.

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
`finish_reason: "tool_calls"`. Now send the follow-up call an app would
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
request, not a fixed string. That's `replyFromToolResult` at work: llmsim
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
    "receivedAtEpochMillis": 1732000000000
  }
]
```

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
(the compiled *engine*, meant to be used as a build-time dependency — see
below). Your own project's script never needs to live inside llmsim's
repo, and llmsim's engine source never needs to be copied into yours.

Your project gets its own tiny `Dockerfile` (e.g. `llmsim/Dockerfile` in
your repo) that layers just your script on top of the published engine:

```dockerfile
FROM ghcr.io/pramalin/llmsim-build:0.1.0 AS build
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
  Script.scala           -- the DSL: Step, Overrun, Script, ScriptSource
  ScriptRunner.scala     -- advances through a Script's steps, one per call
  CallJournal.scala      -- records every call for later inspection
  Simulator.scala        -- http4s routes: /v1/chat/completions, /v1/messages
  ManagementRoutes.scala -- test-harness routes: /_llmsim/calls, /status, /reset
  App.scala              -- combines both route sets into one HttpApp
  Main.scala             -- loads a script by name (LLMSIM_SCRIPT) and serves it
  scripts/
    Default.scala        -- the built-in fallback script
    WeatherFlow.scala    -- an example fixed multi-call sequence

src/test/scala/com/alai/llmsim/
  SimulatorSpec.scala           -- in-process tests of the simulator itself
  PublishedApiContractSpec.scala -- checks our case classes decode example
                                     payloads shaped like each vendor's own
                                     published API docs (no network, no keys)
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
git tag v0.1.0
git push origin v0.1.0
```

This builds and pushes `ghcr.io/pramalin/llmsim-build:0.1.0` (the engine,
for other projects to build their own scripts against — see "Using
llmsim in an app's end-to-end tests" above) and `ghcr.io/pramalin/llmsim:0.1.0`
(the standalone image), each also tagged `:latest`.

## Testing

```
sbt test
```

Everything runs with no network access and no API keys — `SimulatorSpec`
exercises the routes in-process, and `PublishedApiContractSpec` checks our
case classes against example payloads shaped like each vendor's published
docs rather than making a live call.

## Roadmap

1. ~~Single canned response~~ — done
2. ~~Scriptable responses~~ — done
3. ~~Captured-call journal~~ — done (`/_llmsim/calls`, `/_llmsim/status`, `/_llmsim/reset`)
4. ~~Tool-call round trips~~ — done (`toolCall`, and `replyFromToolResult`
   to build a reply from the app's real tool result rather than a fixed string)
5. ~~Published, reusable distribution~~ — done (`ghcr.io/pramalin/llmsim-build`
   as a dependency for consuming projects, `ghcr.io/pramalin/llmsim` standalone)
6. Streaming responses (SSE)
7. Fault injection: artificial latency, HTTP failure variety beyond fixed
   status/message, truncated streams
8. Per-provider or per-test-run session isolation, so concurrent tests
   don't share one script position (currently global to the process)
9. `GET /v1/models`, token-usage refinements, execution trace / timeline
   view over the call journal
