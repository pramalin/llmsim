# Console framework decision (roadmap item 16)

Design record, not user documentation. Captures *why* Tyrian was chosen
over Laminar, Angular, and React for the eventual real console, and the
architectural decisions that follow from it -- so this doesn't need to
be re-argued when item 16 actually starts.

Finalized here, not earlier: the original draft of this decision was
made before fault injection (roadmap item 14) existed. Item 14 is now
fully shipped (`v0.4.0`) -- the shapes this doc's Model sketch depends
on are real, not hypothetical, and are captured exactly as shipped in
"The real shapes to design against" below.

## Decision

**Tyrian** (Elm-architecture, Scala.js, `cats.effect.IO` under `Cmd`/
`Sub`), not Angular, not React, not Laminar.

## Why not Angular or React

Ruled out early, independent of which Scala.js library eventually won:
llmsim's console isn't meant to showcase mainstream frontend skills or
enterprise breadth -- it exists to make an AI-integration test harness
observable and controllable, nothing more. Angular explicitly positions
itself as a broad application platform for large teams; that's real
structure this console doesn't need. React would work, but it's a
component library, not an architecture -- routing, HTTP state, and
project structure would all be separate decisions layered on top,
without buying back anything llmsim specifically needs. Both would also
mean maintaining a second, hand-written copy of the JSON contract
(TypeScript interfaces) with nothing structurally preventing it from
drifting out of sync with the actual Scala case classes -- exactly the
kind of protocol-drift bug this project has already been bitten by more
than once (the OpenAI/Anthropic content-decoding asymmetry, the
Anthropic null-field cleanup, the streaming whitespace bug).

## Why Tyrian over Laminar

Both are legitimate choices, and both solve the shared-domain-model
problem equally well -- that argument doesn't distinguish between them.
What does:

**The console's actual shape is closer to a state machine than a
reactive website.** It's an event-history inspector, a state viewer, a
request/response drill-down, a control surface for reset/clear/fault
actions, and a streaming-lifecycle visualizer -- discrete states
(connected/disconnected/reconnecting; pending/success/failure) driven by
discrete events, not many independently-updating widgets. Tyrian's
Model/Msg/update/view loop makes every state transition an explicit,
pure function -- see "The real shapes to design against" below for
what the actual `Model`/`Msg` now look like, informed by real shipped
types rather than a sketch.

`update(model, StreamDisconnected)` is then a pure function, testable
with no browser at all -- valuable specifically for a *testing tool*,
where the console's own correctness (does it represent a mid-stream
disconnect right, does a reset actually clear pending-action state)
matters as much as its usability.

**A close, real precedent exists.** A 2025 Google Summer of Code project
(workflows4s, mentored by Tyrian's own creator) built -- in their own
words -- "a lightweight, functional, and developer-friendly dashboard
for workflow inspection and debugging" using Tyrian. That's structurally
close enough to llmsim's console to count as real evidence, not just a
similarity of vibe.

**One correction against an earlier, overstated version of this
argument**: Tyrian is not "ordinary Cats Effect programming" on the
frontend. Its `tyrian-io` module and `TyrianIOApp` do let `Cmd`/`Sub`
use `cats.effect.IO`, but effects live behind that boundary --
`update`/`view` stay pure, and application code mostly doesn't touch
`IO` directly. There's still a real, Tyrian-specific mental model
(browser event → `Msg` → pure `update` → new `Model` + `Cmd` → runtime
executes → another `Msg`). That's not a weakness -- it may be the
actual point, for a tool where explicit, inspectable state transitions
matter -- but it should be understood as the reason to prefer Tyrian
(explicit MVU architecture), not "it uses the same effect type as the
backend" on its own, which is a real but secondary advantage.

## Where Laminar remains the stronger fallback

If a Tyrian vertical slice runs into genuine friction -- specifically,
if the console turns out to need many independently and rapidly updating
widgets rather than a manageable number of discrete states -- Laminar
is the fallback, not Angular or React. It has the larger Scala.js
community, an official Scala.js-site tutorial, a no-virtual-DOM model
well suited to continuously changing values, and a polished full-stack
reference project (Scala.js + http4s + shared code + Vite + Docker).
Virtual-DOM-vs-real-DOM is not expected to matter at this project's
scale (even a full 1000-entry journal) -- pagination and filtering will
matter far more than the rendering mechanism either framework uses.

## The real shapes to design against

Everything below is copied from the shipped source, not sketched from
memory -- the console's `Model`/`Msg` should be designed against these
exactly, not a paraphrase of them.

`CallOutcome` (`CallJournal.scala`) -- four cases, encoded with a flat
`"type"` discriminator on the wire (not circe's default nested-object
shape), specifically so a non-Scala consumer like this console can
decode it without knowing anything about the Scala encoding:

```scala
sealed trait CallOutcome
object CallOutcome {
  final case class Responded(status: Int, body: Json) extends CallOutcome
  final case class Rejected(status: Int, message: String) extends CallOutcome
  final case class Failed(message: String) extends CallOutcome
  final case class Cancelled(message: String) extends CallOutcome
}
```

`CapturedCall` (`CallJournal.scala`) -- what `GET /_llmsim/calls`
actually returns, one entry per call:

```scala
final case class CapturedCall(
    sequence: Long,
    provider: String,
    model: Option[String],
    messages: Vector[CapturedMessage],
    rawRequest: Json,
    outcome: CallOutcome,
    stepIndex: Option[Int],
    receivedAtEpochMillis: Long,
    completedAtEpochMillis: Long,
    durationMillis: Long,               // monotonic-clock elapsed time, not wall-clock subtraction
    responseHeaders: Vector[CapturedHeader] = Vector.empty,
    streamed: Boolean = false
)
```

`StreamFault` (`Script.scala`) -- not something the console reads back
per call today (it's script-side configuration, not part of
`CapturedCall`), but the console's fault-control surface (the "reset,
clear, and fault scenarios" control panel from the original Model
sketch) will need to construct values shaped like this if it ever lets
someone configure a fault interactively rather than only via a Scala
script:

```scala
final case class StreamFault(
    delayBeforeFirstEvent: FiniteDuration = Duration.Zero,
    delayBetweenEvents: FiniteDuration = Duration.Zero,
    heartbeatInterval: FiniteDuration = 15.seconds,
    omitCompletionEvent: Boolean = false,
    malformedEventAt: Option[Int] = None,
    splitToolCallArguments: Int = 1
)
```

No dedicated `StreamLifecycleEvent`/live-event-stream type exists yet --
the console's "streaming timeline" and "connected/disconnected/
reconnecting" states (from the original Model sketch) will need to be
designed against `CapturedCall.outcome`/`streamed`/`durationMillis` as
they exist today, most likely via polling `GET /_llmsim/calls` the same
way the bare-bones dashboard already does, not a push-based event feed
that doesn't exist. Whether a real push-based stream (SSE from
`/_llmsim/...` itself, mirroring the pattern llmsim already uses for
its own simulated responses) is worth building is a design question for
the vertical slice itself, not something to assume the answer to here.

## Module structure

```
root
├── llmsim-core
│   └── JVM-only simulator internals (Simulator, ScriptRunner, CallJournal, ...)
│
├── llmsim-management-api
│   └── JVM + Scala.js cross-project
│       ├── DashboardSummary, ScriptStatus, CallSummary, CallDetails, ...
│       └── circe codecs
│
├── llmsim-server
│   └── http4s routes (ManagementRoutes, Dashboard, Simulator's own routes)
│
└── llmsim-console
    └── Scala.js + Tyrian
```

**Deliberately not sharing internal types wholesale.** `CapturedCall`
and `CallJournal`'s/`ScriptRunner`'s internals may keep evolving for
server-implementation reasons unrelated to what the console needs to
show. `llmsim-management-api` is a deliberate, stable contract layer
between the two, the same boundary `schemaVersion` on `DashboardSummary`
already exists to protect -- not a shortcut to just cross-compile
whatever the server happens to use internally today.

## Build isolation is unaffected either way

Neither framework avoids JS tooling entirely -- Tyrian's own examples
commonly use Parcel/npm, Laminar's reference project uses Vite/Node.
That was never a point in either framework's favor; it's a constraint
that applies the same way regardless of which one is chosen. The
existing plan holds: the console compiles and bundles during llmsim's
own official release build, gets copied into the server's JVM resources
before `llmsim-build`/`llmsim` get published, and a downstream project's
`FROM llmsim-build:<version>` + `COPY MyScript.scala` + `sbt assembly`
never touches Node, Parcel, or a Scala.js compile step. A user writing a
custom script should never need any of this project's frontend tooling
at all.

## Sequencing

**No longer blocked.** The original version of this note said the
bare-bones dashboard should stay exactly as it is "until the
fault-injection and stream-lifecycle contracts actually stabilize" --
that's now true. `StreamFault` and the `Cancelled` outcome (roadmap
item 14) shipped in `v0.4.0`, confirmed end to end including against a
real Spring AI client. The shapes in "The real shapes to design
against" above are the stable contract this console can now be
designed against without expecting them to shift underneath it.

The current bare-bones dashboard (`GET /_llmsim/dashboard` +
`GET /_llmsim/ui`, see `dashboard-design.md`) can stay as-is through the
vertical slice below -- there's no need to retire it before the
replacement is real.

The first real step is **one deliberately realistic Tyrian vertical
slice**, not a trivial spike (a live-polling counter, for instance,
would be too easy in either framework to reveal anything). The slice
should:

1. Decode shared `DashboardSummary`/`CapturedCall` models from
   `llmsim-management-api`.
2. Fetch the dashboard and the captured-call list.
3. Render a call table; select one call and show its JSON detail.
4. Represent `CallOutcome`'s four cases distinctly, including
   `Cancelled` -- the case that didn't exist when this doc was first
   drafted.
5. Execute reset/clear actions with pending/success/failure states.
6. Package the resulting assets into the server's JVM resources, and
   confirm the custom-script build workflow is genuinely untouched.

If that slice stays clean, build the rest on top of it. If it exposes
real friction -- specifically around high-frequency stream events or an
unwieldy `Model` -- that's the trigger to build the same slice in
Laminar and compare directly, not a reason to have started there.
