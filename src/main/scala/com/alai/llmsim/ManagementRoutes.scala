package com.alai.llmsim

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.typelevel.ci.CIString
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import Dashboard._

/** Endpoints for the TEST HARNESS -- never the application under test.
  * These live under /_llmsim/..., a path namespace clearly separate from
  * the simulated vendor paths (/v1/...), so there's no ambiguity about
  * which surface an app might ever hit.
  */
object ManagementRoutes {

  final case class StatusResponse(totalCalls: Int)

  private implicit val capturedCallEncoder: Encoder[CapturedCall] = deriveEncoder
  private implicit val callEntityEncoder: EntityEncoder[IO, CapturedCall] =
    jsonEncoderOf[IO, CapturedCall]
  private implicit val callsEntityEncoder: EntityEncoder[IO, List[CapturedCall]] =
    jsonEncoderOf[IO, List[CapturedCall]]

  private implicit val statusEncoder: Encoder[StatusResponse] = deriveEncoder
  private implicit val statusEntityEncoder: EntityEncoder[IO, StatusResponse] =
    jsonEncoderOf[IO, StatusResponse]

  private implicit val dashboardEntityEncoder: EntityEncoder[IO, DashboardSummary] =
    jsonEncoderOf[IO, DashboardSummary]

  def routes(
      journal: CallJournal,
      runner: ScriptRunner,
      journalCapacity: Int,
      scriptName: Option[String] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // Every call the simulator has answered so far, in order.
      case GET -> Root / "_llmsim" / "calls" =>
        journal.all.flatMap(calls => Ok(calls))

      // One call by its sequence number.
      case GET -> Root / "_llmsim" / "calls" / LongVar(sequence) =>
        journal.find(sequence).flatMap {
          case Some(call) => Ok(call)
          case None       => NotFound(s"no captured call with sequence $sequence")
        }

      // A quick summary, mainly for a human checking things are alive.
      case GET -> Root / "_llmsim" / "status" =>
        journal.all.flatMap(calls => Ok(StatusResponse(totalCalls = calls.size)))

      // Clears the journal ONLY -- script position is untouched, so the
      // simulator carries on from wherever it was.
      case DELETE -> Root / "_llmsim" / "calls" =>
        journal.clear *> Ok("journal cleared")

      // Clears the journal AND rewinds the script back to its first step --
      // lets a test suite reuse one running simulator across many test
      // cases without restarting the container between them.
      case POST -> Root / "_llmsim" / "reset" =>
        for {
          _      <- journal.clear
          _      <- runner.reset
          result <- Ok("reset")
        } yield result

      // The bare-bones dashboard's data (roadmap item 13). Cache-Control:
      // no-store since this is meant to be polled live, not cached by an
      // intermediary -- matches the page's own fetch(..., {cache:
      // "no-store"}).
      case GET -> Root / "_llmsim" / "dashboard" =>
        for {
          calls   <- journal.all
          status  <- runner.status
          summary =  Dashboard.summarize(calls, journalCapacity, status, scriptName)
          result  <- Ok(summary).map(_.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store")))
        } yield result

      // The dashboard's page -- a single static HTML file with no build
      // step, served at the same path the eventual Angular console
      // (roadmap item 16) will occupy later.
      case GET -> Root / "_llmsim" / "ui" =>
        Ok(Dashboard.htmlPage).map(_.putHeaders(Header.Raw(CIString("Content-Type"), "text/html; charset=utf-8")))
    }
}
