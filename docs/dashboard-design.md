# Dashboard design (roadmap item 13)

Design record, not user documentation -- see the README's own section
(once this ships) for how to actually use `/_llmsim/dashboard` and
`/_llmsim/ui`. This captures the *why*, including a couple of decisions
that changed from the first draft after review.

## What this is, and isn't

Two new, read-only `GET` routes:

- `GET /_llmsim/dashboard` -- a JSON summary of the journal's current
  contents plus script progress.
- `GET /_llmsim/ui` -- a single static HTML page (no build step, no
  framework) that polls and renders that JSON.

`/_llmsim/ui` deliberately reuses the exact path the roadmap already
earmarked for the eventual Angular console (item 16). This page occupies
that URL now and gets replaced later; nothing that links to it needs to
change when that happens.

Explicitly out of scope, matching "bare-bones": no charts, no per-call
drill-down beyond what `/_llmsim/calls` already provides, no session
view (no session concept exists -- concurrent test isolation is handled
by ephemeral instances per test class, not an in-process session model;
see the roadmap's note on that decision).

## The JSON contract

```json
{
  "schemaVersion": 1,
  "script": {
    "name": "com.alai.llmsim.scripts.WeatherFlow",
    "totalSteps": 9,
    "nextStepIndex": 3,
    "onOverrun": "fail",
    "exhausted": false
  },
  "journal": {
    "retainedCalls": 12,
    "capacity": 1000
  },
  "calls": {
    "byOutcome": { "responded": 10, "rejected": 1, "failed": 1 },
    "byProvider": { "openai": 7, "anthropic": 5 },
    "streamed": 4
  },
  "latencyMillis": {
    "sampleCount": 12,
    "average": 8.5,
    "p95": 22,
    "max": 45
  },
  "lastCallAtEpochMillis": 1732000123456
}
```

`schemaVersion` costs nothing now and gives a future Angular client (or
anything else parsing this) an explicit compatibility signal if the
shape ever needs to change.

`byOutcome`/`byProvider` always include every known key (`responded`/
`rejected`/`failed`; `openai`/`anthropic`), even at zero -- a client
reading this shouldn't need to handle "key absent" as a separate case
from "key present with value 0."

`average`/`p95`/`max`/`lastCallAtEpochMillis` are `null`, not `0`, when
the journal is empty. A freshly booted, idle simulator should read as
"no data yet," not "average latency 0ms" -- a real, wrong answer to a
question nobody asked.

## Everything here is a journal-window metric, not a lifetime count

The first draft used `"totalCalls"`. That name implies a lifetime
counter, which this isn't: the journal is intentionally bounded (oldest
entries dropped past `capacity`) and can be cleared independently of the
script (`DELETE /_llmsim/calls`, vs `POST /_llmsim/reset` which also
rewinds the script). Every count and latency figure is scoped to
whatever calls are *currently retained*, which is why the field is
`journal.retainedCalls`, not a bare `totalCalls` -- and why `capacity`
sits right next to it, so that scope is visible in the payload itself,
not just in a doc someone has to already know to go read.

## `ScriptStatus`, not a raw position integer

The first draft planned `ScriptRunner.currentPosition: IO[Int]`, exposing
the runner's internal position counter directly. Tracing through the
actual `next` implementation showed why that's a real leaky abstraction,
not just a style question:

- The internal position **freezes** at `totalSteps` once a script is
  exhausted, for *both* `Overrun.Fail` and `Overrun.RepeatLast`. A raw
  reading of `9` (say) is genuinely ambiguous from outside -- is the
  script done-and-erroring, or done-and-repeating-the-last-step
  forever? You can't tell without also knowing `onOverrun`.
- For `Overrun.Cycle`, the position never even stays at `totalSteps` --
  it's corrected back to `1` within the same atomic step that answers
  the wraparound call. An external reader would see `1` and have no way
  to know a wraparound had just happened, versus the script simply being
  two calls into a normal run.

`ScriptRunner` owns both the position and the overrun policy, so it
should hand back the *meaningful interpretation*, computed where that
context already lives:

```scala
final case class ScriptStatus(
    totalSteps: Int,
    nextStepIndex: Option[Int],
    onOverrun: Overrun,
    exhausted: Boolean
)
```

| State | `nextStepIndex` | `exhausted` |
|---|---|---|
| still running, position `i < totalSteps` | `Some(i)` | `false` |
| `Fail`, exhausted | `None` | `true` |
| `RepeatLast`, exhausted | `Some(totalSteps - 1)` | `false` |
| `Cycle`, exhausted (transiently) | `Some(0)` | `false` |

Confirmed by tracing the actual `next` code that the internal position
can never exceed `totalSteps` (it freezes there for `Fail`/`RepeatLast`,
and is corrected back down immediately for `Cycle`) -- so this three-way
case split is complete; there's no fourth state to handle.

`onOverrun` is carried as the real `Overrun` type here, not pre-stringified
-- the JSON string encoding (`"fail"`/`"repeatLast"`/`"cycle"`, the actual
Scala case-object names with just the first letter lowercased, not
Scala's raw `.toString`) belongs in `Dashboard`'s encoder, not baked into
the domain model.

## `scriptName` is cosmetic, and optional everywhere

`App.build` gained a third parameter, `scriptName: Option[String] = None`.
`Main.scala` already resolves the script's fully-qualified class name
(`className`) before calling `App.build` -- passing it through is a
one-line change there. Every other call site (all of them, in tests)
calls `App.build(script)` with no third argument and keeps compiling
unchanged, since the default is `None`. When absent, the dashboard's
`script.name` is `null` -- a minor UX gap for in-process tests, not a
missing feature, since those never had a "wrong script loaded" failure
mode to guard against in the first place.

## Percentile method

Nearest-rank: the `ceil(sampleSize * p)`-th smallest value (1-indexed),
converted to a 0-indexed lookup into the ascending-sorted durations.
Standard, simple, and honest about small samples -- p95 of 12 values
legitimately lands on the max; that's the correct answer for a sample
that small, not a flaw in the method.

## Cache-Control and Content-Type

`GET /_llmsim/dashboard` sends `Cache-Control: no-store` -- this is
meant to be polled live, not cached by any intermediary between the
page and llmsim. The page's own `fetch` call matches with
`{ cache: "no-store" }`.

`GET /_llmsim/ui` sends `Content-Type: text/html; charset=utf-8` via a
raw header (`Header.Raw`), the same pattern used for the SSE endpoints'
headers elsewhere in this codebase -- consistent with this project's
established preference for raw headers over http4s's typed
`MediaType`/`Content-Type` helpers in spots where the exact API surface
hasn't already been directly verified against this http4s version.

## The static page itself

Single Scala triple-quoted string (`Dashboard.htmlPage`), inline CSS, a
small amount of vanilla JS. Recursive `setTimeout` after each request
*completes*, not `setInterval` -- so a slow request can never stack
overlapping fetches. A visible "last refreshed" timestamp and an error
banner on a failed poll, so a broken connection reads as "this is
stale/broken," not as a dashboard that's silently frozen while still
looking valid.

One real implementation trap worth recording: Scala triple-quoted
strings do **not** process backslash escapes at all. An early draft of
the embedded JS used double-quoted JS string literals for HTML
fragments, needing escaped quotes inside them (`\"`) -- written as `\\"`
in the Scala source in the mistaken expectation that Scala would
collapse it to a single escaped quote in the output, the way an ordinary
(non-triple-quoted) string would. It doesn't: what's typed between
`"""..."""` comes out byte-for-byte. The actual output contained a
literal `\\"` (two backslashes and a quote), which is invalid inside a
JS string and would have broken the page's `<script>` block outright at
runtime -- something `sbt test`'s Scala-level checks have no way to
catch, since the string itself compiles fine as a `String` value
regardless of whether the JS text it contains is valid. Caught by
actually extracting and running the embedded JS with `node`, both
`node --check` for syntax and a mocked-DOM execution of `render()`
against sample data matching the documented contract, rather than by
inspecting the Scala source. Fixed by rewriting the JS to use
single-quoted string literals throughout, which need no escaping
against HTML's double-quoted attributes at all -- more robust than
getting the backslash count exactly right a second time.

## Test coverage

`DashboardSpec.scala`: the pure `Dashboard.summarize` function against
hand-built fixtures (empty journal; mixed providers/outcomes/streaming;
single-call edge case where average/p95/max all coincide; a 20-sample
fixture with a hand-verified nearest-rank p95; all three `ScriptStatus`
overrun states plus the exhausted case), `ScriptRunner.status` end to
end for all four script states (running, Fail-exhausted, RepeatLast,
Cycle, reset), and the two routes themselves (content type, Cache-Control,
a small-capacity journal proving stats reflect only retained calls, and
`DELETE /_llmsim/calls` vs `POST /_llmsim/reset` producing the documented
different effects on `journal.retainedCalls` vs `script.nextStepIndex`).
