package com.alai.llmsim

import io.circe.{Codec, Json}
import io.circe.generic.semiauto.deriveCodec

/** One call the simulator received.
  *
  * `rawRequest` is the JSON exactly as sent -- for anything llmsim
  * doesn't itself model. `model`/`messages` are normalized so a test
  * doesn't need to know both vendors' shapes just to check what was
  * asked. `stepIndex` is which script step answered it (0-based), or
  * `None` for Overrun.Fail or a Failed decode -- there was no step to
  * attribute it to.
  *
  * Lives in `common`, not `CallJournal.scala` -- step 7 (the last type
  * move) of the console reorganization. Unlike the other three moved
  * types, this one previously had no companion-object codec at all --
  * every consumer (SimulatorSpec.scala, DashboardSpec.scala,
  * DisconnectSpec.scala) declared its own local `deriveDecoder`
  * instead. Adding one here now, matching CapturedHeader/
  * CapturedMessage/CallOutcome's pattern, so the console (and any
  * future consumer) gets a working Decoder for free via companion-
  * object implicit resolution. The existing test files' local
  * declarations are left alone for this step -- redundant now, but
  * harmless, and removing them is a separate cleanup, not part of
  * this move.
  */
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
    // From a monotonic clock, not (completedAtEpochMillis - receivedAtEpochMillis)
    // -- real/wall-clock time can jump (NTP adjustment, clock skew) and
    // is the wrong source for measuring elapsed duration.
    durationMillis: Long,
    // Empty for the common case (no script-level headers, or a
    // synthetic llmsim-internal error where the step's own headers
    // don't apply).
    responseHeaders: Vector[CapturedHeader] = Vector.empty,
    // True if this call was answered as SSE (request had "stream": true
    // and got a text/event-stream response). outcome.body still records
    // the same logical response shape a non-streaming call would have
    // gotten -- this flag is the only thing that tells a reader which
    // transport was actually used.
    streamed: Boolean = false
)
object CapturedCall {
  implicit val codec: Codec[CapturedCall] = deriveCodec
}
