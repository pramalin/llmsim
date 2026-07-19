package com.alai.llmsim

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

/** The simulator itself. Two routes, each mirroring the shape of a real
  * vendor endpoint closely enough that a client library pointed at
  * `http://localhost:8089` instead of the real host shouldn't notice --
  * and, just as importantly, neither route accepts anything from the
  * caller that would give away that it's a simulator. There is no
  * scenario-selecting header and no runtime configuration endpoint: the
  * ONLY thing that decides what a call gets back is the Script this
  * instance was started with (see Main.scala). Configuration lives
  * entirely at startup, out of band from the traffic this serves.
  *
  * Every call is recorded in the CallJournal before it's answered --
  * including ones whose body couldn't be decoded -- so a test can
  * inspect afterward exactly what was sent. See ManagementRoutes.
  */
object Simulator {

  private implicit val jsonRequestDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  def routes(runner: ScriptRunner, journal: CallJournal): HttpRoutes[IO] = {

    def recordFailureAndReject(provider: String, json: Json, reason: String): IO[Response[IO]] = {
      val message = s"llmsim: could not decode $provider request body: $reason"
      val errorJson = Json.obj(
        "error" -> Json.obj(
          "message" -> Json.fromString(message),
          "type"    -> Json.fromString("invalid_request")
        )
      )
      for {
        _      <- journal.record(provider, None, Vector.empty, json, CallOutcome.Failed(message), None)
        result <- errorResponse(400, errorJson)
      } yield result
    }

    HttpRoutes.of[IO] {

      // -----------------------------------------------------------------
      // OpenAI-shaped endpoint
      // -----------------------------------------------------------------
      case req @ POST -> Root / "v1" / "chat" / "completions" =>
        for {
          json   <- req.as[Json]
          result <- json.as[OpenAI.ChatRequest] match {
                      case Left(decodeError) =>
                        recordFailureAndReject("openai", json, decodeError.getMessage)

                      case Right(body) =>
                        val (model, messages) = normalizeOpenAI(body)
                        for {
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text), idx) =>
                                         val response = OpenAI.ChatResponse(
                                           id = s"chatcmpl-sim-${UUID.randomUUID()}",
                                           created = Instant.now().getEpochSecond,
                                           model = body.model,
                                           choices = List(
                                             OpenAI.Choice(
                                               index = 0,
                                               message = OpenAI.Message(role = "assistant", content = text),
                                               finish_reason = "stop"
                                             )
                                           ),
                                           usage = fakeUsage(body.messages.map(_.content).mkString(" "), text)
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- journal.record("openai", model, messages, json,
                                                        CallOutcome.Responded(200, responseJson), Some(idx))
                                           result <- Ok(responseJson)
                                         } yield result

                                       case NextStep.Answer(Step.Error(status, message), idx) =>
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message)).asJson
                                         for {
                                           _      <- journal.record("openai", model, messages, json,
                                                        CallOutcome.Rejected(status, message), Some(idx))
                                           result <- errorResponse(status, errorJson)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message, "script_exhausted")).asJson
                                         for {
                                           _      <- journal.record("openai", model, messages, json,
                                                        CallOutcome.Rejected(500, message), None)
                                           result <- errorResponse(500, errorJson)
                                         } yield result
                                     }
                        } yield result
                    }
        } yield result

      // -----------------------------------------------------------------
      // Anthropic-shaped endpoint
      // -----------------------------------------------------------------
      case req @ POST -> Root / "v1" / "messages" =>
        for {
          json   <- req.as[Json]
          result <- json.as[Anthropic.MessagesRequest] match {
                      case Left(decodeError) =>
                        recordFailureAndReject("anthropic", json, decodeError.getMessage)

                      case Right(body) =>
                        val (model, messages) = normalizeAnthropic(body)
                        for {
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text), idx) =>
                                         val response = Anthropic.MessagesResponse(
                                           id = s"msg-sim-${UUID.randomUUID()}",
                                           content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                           model = body.model,
                                           stop_reason = "end_turn",
                                           usage = {
                                             val u = fakeUsage(body.messages.flatMap(_.content.flatMap(_.text)).mkString(" "), text)
                                             Anthropic.Usage(input_tokens = u.prompt_tokens, output_tokens = u.completion_tokens)
                                           }
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- journal.record("anthropic", model, messages, json,
                                                        CallOutcome.Responded(200, responseJson), Some(idx))
                                           result <- Ok(responseJson)
                                         } yield result

                                       case NextStep.Answer(Step.Error(status, message), idx) =>
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("simulated_error", message)).asJson
                                         for {
                                           _      <- journal.record("anthropic", model, messages, json,
                                                        CallOutcome.Rejected(status, message), Some(idx))
                                           result <- errorResponse(status, errorJson)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("script_exhausted", message)).asJson
                                         for {
                                           _      <- journal.record("anthropic", model, messages, json,
                                                        CallOutcome.Rejected(500, message), None)
                                           result <- errorResponse(500, errorJson)
                                         } yield result
                                     }
                        } yield result
                    }
        } yield result
    }
  }

  private def normalizeOpenAI(req: OpenAI.ChatRequest): (Option[String], Vector[CapturedMessage]) =
    (Some(req.model), req.messages.map(m => CapturedMessage(m.role, m.content)).toVector)

  private def normalizeAnthropic(req: Anthropic.MessagesRequest): (Option[String], Vector[CapturedMessage]) =
    (Some(req.model), req.messages.map { m =>
      CapturedMessage(m.role, m.content.flatMap(_.text).mkString(" "))
    }.toVector)

  private def errorResponse(statusCode: Int, body: Json): IO[Response[IO]] = {
    val status = Status.fromInt(statusCode).getOrElse(Status.InternalServerError)
    Response[IO](status).withEntity(body).pure[IO]
  }

  // Not a real tokenizer -- word count is a stand-in so downstream business
  // logic that reads `usage` has *something* plausible to assert against.
  private def fakeUsage(promptText: String, completionText: String): OpenAI.Usage = {
    val promptTokens     = promptText.split("\\s+").count(_.nonEmpty)
    val completionTokens = completionText.split("\\s+").count(_.nonEmpty)
    OpenAI.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
  }
}
