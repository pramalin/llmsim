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
response."` in `choices[0].message.content`. The same request against
`http://localhost:8089/v1/messages` (Anthropic's shape) gets the same
text back in `content[0].text` — one running instance answers both.

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

## Layout

```
src/main/scala/com/alai/llmsim/
  Protocol.scala       -- case classes for OpenAI ChatRequest/Response and
                           Anthropic MessagesRequest/Response, plus their
                           error-response shapes, with circe codecs inline
  Script.scala          -- the DSL: Step, Overrun, Script, ScriptSource
  ScriptRunner.scala    -- advances through a Script's steps, one per call
  Simulator.scala       -- http4s routes: /v1/chat/completions, /v1/messages
  Main.scala            -- loads a script by name (LLMSIM_SCRIPT) and serves it
  scripts/
    Default.scala       -- the built-in fallback script
    WeatherFlow.scala   -- an example fixed multi-call sequence

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

(only add `--build` if the script is a new file that isn't in the image yet).

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
3. Multi-turn state: `tool_use` -> `tool_result` round trips, so a script
   step can be a tool call and the simulator can react to the tool result
   your app sends back in the next request
4. Streaming responses (SSE)
5. Fault injection beyond fixed-status errors: artificial latency,
   truncated streams, malformed JSON
6. A way to inspect, after a test run, exactly what your app sent at each
   step — turning this into a regression harness for agent behavior
