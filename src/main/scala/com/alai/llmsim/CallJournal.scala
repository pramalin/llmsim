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
    receivedAtEpochMillis: Long
)

trait CallJournal {
  def record(
      provider: String,
      model: Option[String],
      messages: Vector[CapturedMessage],
      rawRequest: Json,
      outcome: CallOutcome,
      stepIndex: Option[Int]
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

  /** @param maxEntries oldest entries are dropped once the journal holds
    *                    more than this many, so a long-running simulator
    *                    can't grow its call log without bound.
    */
  def inMemory(maxEntries: Int = DefaultMaxEntries): IO[CallJournal] =
    for {
      callsRef <- Ref.of[IO, Vector[CapturedCall]](Vector.empty)
      seqRef   <- Ref.of[IO, Long](0)
    } yield new CallJournal {
      def record(
          provider: String,
          model: Option[String],
          messages: Vector[CapturedMessage],
          rawRequest: Json,
          outcome: CallOutcome,
          stepIndex: Option[Int]
      ): IO[CapturedCall] =
        for {
          seq  <- seqRef.updateAndGet(_ + 1)
          call =  CapturedCall(seq, provider, model, messages, rawRequest, outcome, stepIndex, System.currentTimeMillis())
          _    <- callsRef.update { existing =>
                    val updated = existing :+ call
                    if (updated.size > maxEntries) updated.drop(updated.size - maxEntries) else updated
                  }
        } yield call

      def all: IO[List[CapturedCall]] = callsRef.get.map(_.toList)

      def find(sequence: Long): IO[Option[CapturedCall]] =
        callsRef.get.map(_.find(_.sequence == sequence))

      def clear: IO[Unit] =
        for {
          _ <- callsRef.set(Vector.empty)
          _ <- seqRef.set(0)
        } yield ()
    }
}
