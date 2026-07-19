package com.alai.llmsim

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** Endpoints for the TEST HARNESS -- never the application under test.
  * These live under /_llmsim/..., a path namespace clearly separate from
  * the simulated vendor paths (/v1/...), so there's no ambiguity about
  * which surface an app might ever hit.
  */
object ManagementRoutes {

  final case class StatusResponse(totalCalls: Int)

  private implicit val capturedCallEncoder: Encoder[CapturedCall] = deriveEncoder
  private implicit val callsEntityEncoder: EntityEncoder[IO, List[CapturedCall]] =
    jsonEncoderOf[IO, List[CapturedCall]]

  private implicit val statusEncoder: Encoder[StatusResponse] = deriveEncoder
  private implicit val statusEntityEncoder: EntityEncoder[IO, StatusResponse] =
    jsonEncoderOf[IO, StatusResponse]

  def routes(journal: CallJournal, runner: ScriptRunner): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // Every call the simulator has answered so far, in order.
      case GET -> Root / "_llmsim" / "calls" =>
        journal.all.flatMap(calls => Ok(calls))

      // A quick summary, mainly for a human checking things are alive.
      case GET -> Root / "_llmsim" / "status" =>
        journal.all.flatMap(calls => Ok(StatusResponse(totalCalls = calls.size)))

      // Clears the journal AND rewinds the script back to its first step --
      // lets a test suite reuse one running simulator across many test
      // cases without restarting the container between them.
      case POST -> Root / "_llmsim" / "reset" =>
        for {
          _      <- journal.reset
          _      <- runner.reset
          result <- Ok("reset")
        } yield result
    }
}
