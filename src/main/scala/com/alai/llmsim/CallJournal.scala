package com.alai.llmsim

import cats.effect.{IO, Ref}
import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.generic.semiauto.deriveCodec

/** A message from the request, in a shape that doesn't require knowing
  * whether it came in as OpenAI's flat `content: String` or Anthropic's
  * `content: List[ContentBlock]` -- both get normalized to this.
  */
final case class CapturedMessage(role: String, content: String)
object CapturedMessage {
  implicit val codec: Codec[CapturedMessage] = deriveCodec
}

/** A response header llmsim actually emitted, most commonly from a
  * script's `headers` field (rate-limit headers, `retry-after`, etc.).
  * `Vector[CapturedHeader]` rather than `Map[String, String]`
  * deliberately -- captured wire data should preserve duplicate names,
  * original casing, and original order, none of which a Map can. The
  * public script DSL still accepts a Map for convenience; this is only
  * what got recorded after the fact.
  */
final case class CapturedHeader(name: String, value: String)
object CapturedHeader {
  implicit val codec: Codec[CapturedHeader] = deriveCodec
}

/** What the simulator did with a call. Three cases:
  *   - Responded: answered normally (a Reply step, or Overrun.RepeatLast/Cycle)
  *   - Rejected: answered with a deliberate error -- either an Error step,
  *     or Overrun.Fail (the script ran out)
  *   - Failed: the request body couldn't even be decoded into the
  *     expected shape; no script step was consumed
  *
  * Encoded as flat JSON with a "type" discriminator (rather than circe's
  * default nested-object encoding for sealed traits), since the intended
  * consumer is a test harness in any language, not just Scala.
  */
sealed trait CallOutcome
object CallOutcome {
  final case class Responded(status: Int, body: Json) extends CallOutcome
  final case class Rejected(status: Int, message: String) extends CallOutcome
  final case class Failed(message: String) extends CallOutcome

  implicit val encoder: Encoder[CallOutcome] = Encoder.instance {
    case Responded(status, body) =>
      Json.obj("type" -> Json.fromString("responded"), "status" -> Json.fromInt(status), "body" -> body)
    case Rejected(status, message) =>
      Json.obj("type" -> Json.fromString("rejected"), "status" -> Json.fromInt(status), "message" -> Json.fromString(message))
    case Failed(message) =>
      Json.obj("type" -> Json.fromString("failed"), "message" -> Json.fromString(message))
  }

  implicit val decoder: Decoder[CallOutcome] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "responded" => for { s <- c.get[Int]("status"); b <- c.get[Json]("body") } yield Responded(s, b)
      case "rejected"  => for { s <- c.get[Int]("status"); m <- c.get[String]("message") } yield Rejected(s, m)
      case "failed"    => c.get[String]("message").map(Failed(_))
      case other       => Left(io.circe.DecodingFailure(s"unknown CallOutcome type: $other", c.history))
    }
  }
}

/** One call the simulator received.
  *
  * `rawRequest` is the JSON exactly as sent -- for anything llmsim
  * doesn't itself model. `model`/`messages` are normalized so a test
  * doesn't need to know both vendors' shapes just to check what was
  * asked. `stepIndex` is which script step answered it (0-based), or
  * `None` for Overrun.Fail or a Failed decode -- there was no step to
  * attribute it to.
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
    // is the wrong source for measuring elapsed duration. See Simulator's
    // recordTimed, which is the only caller of `record` and is what
    // actually captures both clocks.
    durationMillis: Long,
    // Empty for the common case (no script-level headers, or a
    // synthetic llmsim-internal error where the step's own headers
    // don't apply -- see Simulator.scala's recordTimed call sites for
    // which branches populate this).
    responseHeaders: Vector[CapturedHeader] = Vector.empty,
    // True if this call was answered as SSE (request had "stream": true
    // and got a text/event-stream response). outcome.body still records
    // the same logical response shape a non-streaming call would have
    // gotten -- see Simulator.scala's streaming branches -- this flag
    // is the only thing that tells a reader which transport was
    // actually used.
    streamed: Boolean = false
)

trait CallJournal {
  def record(
      provider: String,
      model: Option[String],
      messages: Vector[CapturedMessage],
      rawRequest: Json,
      outcome: CallOutcome,
      stepIndex: Option[Int],
      receivedAtEpochMillis: Long,
      completedAtEpochMillis: Long,
      durationMillis: Long,
      responseHeaders: Vector[CapturedHeader] = Vector.empty,
      streamed: Boolean = false
  ): IO[CapturedCall]

  def all: IO[List[CapturedCall]]
  def find(sequence: Long): IO[Option[CapturedCall]]

  /** Clears the journal only -- does not touch script position. See
    * ManagementRoutes: this backs DELETE /_llmsim/calls, while
    * POST /_llmsim/reset additionally rewinds the ScriptRunner.
    */
  def clear: IO[Unit]
}

object CallJournal {
  val DefaultMaxEntries = 1000

  /** Sequence and calls live in ONE Ref, not two -- recording both in the
    * same atomic `modify` is what guarantees sequence order always
    * matches recording order, even under concurrent requests. Two
    * separate Refs (as an earlier version had) can't give that guarantee:
    * a request could grab a sequence number, then lose the race to append
    * its own call, leaving the journal's array order out of sync with
    * the sequence numbers it handed out.
    */
  private final case class JournalState(nextSequence: Long, calls: Vector[CapturedCall])

  /** @param maxEntries oldest entries are dropped once the journal holds
    *                    more than this many, so a long-running simulator
    *                    can't grow its call log without bound.
    */
  def inMemory(maxEntries: Int = DefaultMaxEntries): IO[CallJournal] =
    Ref.of[IO, JournalState](JournalState(nextSequence = 1L, calls = Vector.empty)).map { stateRef =>
      new CallJournal {
        def record(
            provider: String,
            model: Option[String],
            messages: Vector[CapturedMessage],
            rawRequest: Json,
            outcome: CallOutcome,
            stepIndex: Option[Int],
            receivedAtEpochMillis: Long,
            completedAtEpochMillis: Long,
            durationMillis: Long,
            responseHeaders: Vector[CapturedHeader] = Vector.empty,
            streamed: Boolean = false
        ): IO[CapturedCall] =
          stateRef.modify { state =>
            val call = CapturedCall(
              state.nextSequence, provider, model, messages, rawRequest, outcome, stepIndex,
              receivedAtEpochMillis, completedAtEpochMillis, durationMillis, responseHeaders, streamed
            )
            val retained = (state.calls :+ call).takeRight(maxEntries)
            JournalState(state.nextSequence + 1, retained) -> call
          }

        def all: IO[List[CapturedCall]] = stateRef.get.map(_.calls.toList)

        def find(sequence: Long): IO[Option[CapturedCall]] =
          stateRef.get.map(_.calls.find(_.sequence == sequence))

        def clear: IO[Unit] = stateRef.set(JournalState(nextSequence = 1L, calls = Vector.empty))
      }
    }
}
