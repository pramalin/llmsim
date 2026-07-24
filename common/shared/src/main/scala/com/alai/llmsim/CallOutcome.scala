package com.alai.llmsim

import io.circe.{Decoder, Encoder, Json}

/** What the simulator did with a call. Four cases:
  *   - Responded: answered normally (a Reply step, or Overrun.RepeatLast/Cycle)
  *   - Rejected: answered with a deliberate error -- either an Error step,
  *     or Overrun.Fail (the script ran out)
  *   - Failed: the request body couldn't even be decoded into the
  *     expected shape; no script step was consumed
  *   - Cancelled: a streamed call whose connection closed (client
  *     disconnect) before the stream finished sending. Confirmed real,
  *     not just modeled ahead of time -- see StreamFault's
  *     heartbeatInterval and DisconnectSpec.scala, which bound and
  *     verify disconnect-detection latency against both a raw socket
  *     and a real Spring AI client.
  *
  * Encoded as flat JSON with a "type" discriminator (rather than circe's
  * default nested-object encoding for sealed traits), since the intended
  * consumer is a test harness in any language, not just Scala.
  *
  * Lives in `common`, not `CallJournal.scala` -- step 6 of the console
  * reorganization. Unlike CapturedHeader/CapturedMessage (step 3/5,
  * plain deriveCodec), this one has hand-written encoder/decoder logic
  * for the flat discriminator, moved verbatim -- worth its own careful
  * step rather than assuming it's as simple as the first two.
  */
sealed trait CallOutcome
object CallOutcome {
  final case class Responded(status: Int, body: Json) extends CallOutcome
  final case class Rejected(status: Int, message: String) extends CallOutcome
  final case class Failed(message: String) extends CallOutcome
  final case class Cancelled(message: String) extends CallOutcome

  implicit val encoder: Encoder[CallOutcome] = Encoder.instance {
    case Responded(status, body) =>
      Json.obj("type" -> Json.fromString("responded"), "status" -> Json.fromInt(status), "body" -> body)
    case Rejected(status, message) =>
      Json.obj("type" -> Json.fromString("rejected"), "status" -> Json.fromInt(status), "message" -> Json.fromString(message))
    case Failed(message) =>
      Json.obj("type" -> Json.fromString("failed"), "message" -> Json.fromString(message))
    case Cancelled(message) =>
      Json.obj("type" -> Json.fromString("cancelled"), "message" -> Json.fromString(message))
  }

  implicit val decoder: Decoder[CallOutcome] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "responded" => for { s <- c.get[Int]("status"); b <- c.get[Json]("body") } yield Responded(s, b)
      case "rejected"  => for { s <- c.get[Int]("status"); m <- c.get[String]("message") } yield Rejected(s, m)
      case "failed"    => c.get[String]("message").map(Failed(_))
      case "cancelled" => c.get[String]("message").map(Cancelled(_))
      case other       => Left(io.circe.DecodingFailure(s"unknown CallOutcome type: $other", c.history))
    }
  }
}
