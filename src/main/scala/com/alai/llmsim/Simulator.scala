package com.alai.llmsim

import cats.effect.{Clock, IO}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.typelevel.ci.CIString
import scala.concurrent.duration.FiniteDuration

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
  *
  * A tool_use/tool_call round trip needs nothing new here beyond the
  * ToolCall step itself: the app's follow-up request (carrying the tool
  * result) is just the NEXT call, answered by the next script step,
  * exactly like any other call. There's no "wait for a tool result"
  * logic -- that would be request matching, which we deliberately keep
  * out of the simulator (see the design discussion in the project history).
  */
object Simulator {

  private implicit val jsonRequestDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  def routes(runner: ScriptRunner, journal: CallJournal): HttpRoutes[IO] = {

    // Captures received/completed as real (wall-clock) time -- for
    // timeline timestamps -- and duration from monotonic time, per Cats
    // Effect's own distinction between the two: real time can jump
    // (NTP adjustment, clock skew) and is wrong for measuring elapsed
    // duration, monotonic time can't be turned into a meaningful Unix
    // timestamp. `receivedAt`/`startedAt` are captured once, at the very
    // top of each route's for-comprehension -- before request-body
    // decoding even happens -- so `receivedAtEpochMillis` reflects when
    // the call actually arrived, not when it happened to finish being
    // processed (which is what the previous single-timestamp version
    // effectively recorded, since it stamped time inside `record` itself,
    // after the response was already built).
    def recordTimed(
        provider: String,
        model: Option[String],
        messages: Vector[CapturedMessage],
        rawRequest: Json,
        outcome: CallOutcome,
        stepIndex: Option[Int],
        receivedAt: FiniteDuration,
        startedAt: FiniteDuration
    ): IO[CapturedCall] =
      for {
        finishedAt  <- Clock[IO].monotonic
        completedAt <- Clock[IO].realTime
        call        <- journal.record(
                          provider, model, messages, rawRequest, outcome, stepIndex,
                          receivedAt.toMillis, completedAt.toMillis, (finishedAt - startedAt).toMillis
                        )
      } yield call

    def recordFailureAndReject(
        provider: String, json: Json, reason: String, receivedAt: FiniteDuration, startedAt: FiniteDuration
    ): IO[Response[IO]] = {
      val message = s"llmsim: could not decode $provider request body: $reason"
      val errorJson = Json.obj(
        "error" -> Json.obj(
          "message" -> Json.fromString(message),
          "type"    -> Json.fromString("invalid_request")
        )
      )
      for {
        _      <- recordTimed(provider, None, Vector.empty, json, CallOutcome.Failed(message), None, receivedAt, startedAt)
        result <- errorResponse(400, errorJson)
      } yield result
    }

    HttpRoutes.of[IO] {

      // -----------------------------------------------------------------
      // OpenAI-shaped endpoint
      // -----------------------------------------------------------------
      case req @ POST -> Root / "v1" / "chat" / "completions" =>
        for {
          receivedAt <- Clock[IO].realTime
          startedAt  <- Clock[IO].monotonic
          json   <- req.as[Json]
          result <- json.as[OpenAI.ChatRequest] match {
                      case Left(decodeError) =>
                        recordFailureAndReject("openai", json, decodeError.getMessage, receivedAt, startedAt)

                      case Right(body) =>
                        val (model, messages) = normalizeOpenAI(body)
                        for {
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers), idx) =>
                                         val response = OpenAI.ChatResponse(
                                           id = s"chatcmpl-sim-${UUID.randomUUID()}",
                                           created = Instant.now().getEpochSecond,
                                           model = body.model,
                                           choices = List(
                                             OpenAI.Choice(
                                               index = 0,
                                               message = OpenAI.Message(role = "assistant", content = Some(text)),
                                               finish_reason = "stop"
                                             )
                                           ),
                                           usage = fakeUsage(openAIPromptText(body), text, usageOverride)
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- recordTimed("openai", model, messages, json,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ToolCall(id, name, arguments, usageOverride, headers), idx) =>
                                         val response = OpenAI.ChatResponse(
                                           id = s"chatcmpl-sim-${UUID.randomUUID()}",
                                           created = Instant.now().getEpochSecond,
                                           model = body.model,
                                           choices = List(
                                             OpenAI.Choice(
                                               index = 0,
                                               message = OpenAI.Message(
                                                 role = "assistant",
                                                 content = None,
                                                 tool_calls = Some(List(
                                                   OpenAI.ToolCall(id, "function", OpenAI.FunctionCall(name, arguments))
                                                 ))
                                               ),
                                               finish_reason = "tool_calls"
                                             )
                                           ),
                                           usage = fakeUsage(openAIPromptText(body), s"$name($arguments)", usageOverride)
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- recordTimed("openai", model, messages, json,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ReplyFromToolResult(toolCallId, render, usageOverride, headers), idx) =>
                                         findOpenAIToolResult(body, toolCallId) match {
                                           case None =>
                                             val message = s"llmsim: this script step expected a tool_result for " +
                                               s"tool_call_id '$toolCallId', but none was found in the request."
                                             val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message, "missing_tool_result")).asJson
                                             for {
                                               _      <- recordTimed("openai", model, messages, json,
                                                            CallOutcome.Failed(message), Some(idx), receivedAt, startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Some(resultText) =>
                                             val text = render(resultText)
                                             val response = OpenAI.ChatResponse(
                                               id = s"chatcmpl-sim-${UUID.randomUUID()}",
                                               created = Instant.now().getEpochSecond,
                                               model = body.model,
                                               choices = List(
                                                 OpenAI.Choice(
                                                   index = 0,
                                                   message = OpenAI.Message(role = "assistant", content = Some(text)),
                                                   finish_reason = "stop"
                                                 )
                                               ),
                                               usage = fakeUsage(openAIPromptText(body), text, usageOverride)
                                             )
                                             val responseJson = response.asJson
                                             for {
                                               _      <- recordTimed("openai", model, messages, json,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.Error(status, message, headers), idx) =>
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message)).asJson
                                         for {
                                           _      <- recordTimed("openai", model, messages, json,
                                                        CallOutcome.Rejected(status, message), Some(idx), receivedAt, startedAt)
                                           result <- errorResponse(status, errorJson, headers)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message, "script_exhausted")).asJson
                                         for {
                                           _      <- recordTimed("openai", model, messages, json,
                                                        CallOutcome.Rejected(500, message), None, receivedAt, startedAt)
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
          receivedAt <- Clock[IO].realTime
          startedAt  <- Clock[IO].monotonic
          json   <- req.as[Json]
          result <- json.as[Anthropic.MessagesRequest] match {
                      case Left(decodeError) =>
                        recordFailureAndReject("anthropic", json, decodeError.getMessage, receivedAt, startedAt)

                      case Right(body) =>
                        val (model, messages) = normalizeAnthropic(body)
                        for {
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers), idx) =>
                                         val response = Anthropic.MessagesResponse(
                                           id = s"msg-sim-${UUID.randomUUID()}",
                                           content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                           model = body.model,
                                           stop_reason = "end_turn",
                                           usage = anthropicUsage(anthropicPromptText(body), text, usageOverride)
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- recordTimed("anthropic", model, messages, json,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ToolCall(id, name, arguments, usageOverride, headers), idx) =>
                                         parseJson(arguments) match {
                                           case Left(parseError) =>
                                             val message =
                                               s"llmsim: this script's ToolCall step (name=$name) has arguments that " +
                                                 s"aren't valid JSON ('$arguments'), which can't be represented in " +
                                                 s"Anthropic's tool_use.input field -- that field is a real nested JSON " +
                                                 s"object at the wire level, not a string. This step can only be used " +
                                                 s"against the OpenAI-shaped endpoint. (${parseError.getMessage})"
                                             val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("simulator_error", message)).asJson
                                             for {
                                               _      <- recordTimed("anthropic", model, messages, json,
                                                            CallOutcome.Failed(message), Some(idx), receivedAt, startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Right(inputJson) =>
                                             val response = Anthropic.MessagesResponse(
                                               id = s"msg-sim-${UUID.randomUUID()}",
                                               content = List(
                                                 Anthropic.ContentBlock(`type` = "tool_use", id = Some(id), name = Some(name), input = Some(inputJson))
                                               ),
                                               model = body.model,
                                               stop_reason = "tool_use",
                                               usage = anthropicUsage(anthropicPromptText(body), s"$name(${inputJson.noSpaces})", usageOverride)
                                             )
                                             val responseJson = response.asJson
                                             for {
                                               _      <- recordTimed("anthropic", model, messages, json,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.ReplyFromToolResult(toolCallId, render, usageOverride, headers), idx) =>
                                         findAnthropicToolResult(body, toolCallId) match {
                                           case None =>
                                             val message = s"llmsim: this script step expected a tool_result for " +
                                               s"tool_use_id '$toolCallId', but none was found in the request."
                                             val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("missing_tool_result", message)).asJson
                                             for {
                                               _      <- recordTimed("anthropic", model, messages, json,
                                                            CallOutcome.Failed(message), Some(idx), receivedAt, startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Some(resultText) =>
                                             val text = render(resultText)
                                             val response = Anthropic.MessagesResponse(
                                               id = s"msg-sim-${UUID.randomUUID()}",
                                               content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                               model = body.model,
                                               stop_reason = "end_turn",
                                               usage = anthropicUsage(anthropicPromptText(body), text, usageOverride)
                                             )
                                             val responseJson = response.asJson
                                             for {
                                               _      <- recordTimed("anthropic", model, messages, json,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), receivedAt, startedAt)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.Error(status, message, headers), idx) =>
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("simulated_error", message)).asJson
                                         for {
                                           _      <- recordTimed("anthropic", model, messages, json,
                                                        CallOutcome.Rejected(status, message), Some(idx), receivedAt, startedAt)
                                           result <- errorResponse(status, errorJson, headers)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("script_exhausted", message)).asJson
                                         for {
                                           _      <- recordTimed("anthropic", model, messages, json,
                                                        CallOutcome.Rejected(500, message), None, receivedAt, startedAt)
                                           result <- errorResponse(500, errorJson)
                                         } yield result
                                     }
                        } yield result
                    }
        } yield result
    }
  }

  // ---------------------------------------------------------------------
  // Journal normalization: flatten either vendor's message shape down to
  // CapturedMessage's simple (role, content: String) view. Deliberately
  // minimal -- rawRequest already preserves everything losslessly, so
  // this is just a convenience projection, not a complete model of tool
  // calls/results. See CallJournal.scala.
  // ---------------------------------------------------------------------

  private def normalizeOpenAI(req: OpenAI.ChatRequest): (Option[String], Vector[CapturedMessage]) =
    (Some(req.model), req.messages.map(m => CapturedMessage(m.role, flattenOpenAIMessage(m))).toVector)

  private def flattenOpenAIMessage(m: OpenAI.Message): String =
    m.content.getOrElse(
      m.tool_calls.fold("")(_.map(tc => s"[tool_call ${tc.function.name}(${tc.function.arguments})]").mkString(" "))
    )

  private def openAIPromptText(req: OpenAI.ChatRequest): String =
    req.messages.map(flattenOpenAIMessage).mkString(" ")

  private def normalizeAnthropic(req: Anthropic.MessagesRequest): (Option[String], Vector[CapturedMessage]) =
    (Some(req.model), req.messages.map(m => CapturedMessage(m.role, flattenAnthropicMessage(m))).toVector)

  private def flattenAnthropicMessage(m: Anthropic.Message): String =
    m.content.map(flattenAnthropicBlock).mkString(" ")

  private def flattenAnthropicBlock(block: Anthropic.ContentBlock): String =
    block.text.getOrElse {
      block.`type` match {
        case "tool_use" =>
          s"[tool_call ${block.name.getOrElse("?")}(${block.input.map(_.noSpaces).getOrElse("{}")})]"
        case "tool_result" =>
          s"[tool_result ${block.tool_use_id.getOrElse("?")}: ${block.content.map(_.noSpaces).getOrElse("")}]"
        case _ => ""
      }
    }

  private def anthropicPromptText(req: Anthropic.MessagesRequest): String =
    req.messages.map(flattenAnthropicMessage).mkString(" ")

  private def anthropicUsage(promptText: String, completionText: String, usageOverride: Option[UsageOverride]): Anthropic.Usage = {
    val u = fakeUsage(promptText, completionText, usageOverride)
    Anthropic.Usage(input_tokens = u.prompt_tokens, output_tokens = u.completion_tokens)
  }

  // ---------------------------------------------------------------------
  // Tool-result extraction for Step.ReplyFromToolResult. llmsim never
  // calls any tool itself -- this just reads a value the app already put
  // in its own request, the same way the app would hand it to a real LLM.
  // ---------------------------------------------------------------------

  private def findOpenAIToolResult(body: OpenAI.ChatRequest, toolCallId: String): Option[String] =
    body.messages
      .find(m => m.role == "tool" && m.tool_call_id.contains(toolCallId))
      .flatMap(_.content)

  private def findAnthropicToolResult(body: Anthropic.MessagesRequest, toolCallId: String): Option[String] =
    body.messages
      .flatMap(_.content)
      .find(b => b.`type` == "tool_result" && b.tool_use_id.contains(toolCallId))
      .flatMap(_.content)
      .map(jsonToPlainText)

  // A tool_result's content can be a plain JSON string or a structured
  // array of content blocks -- this handles either without needing a
  // richer model than CapturedMessage already has.
  private def jsonToPlainText(json: Json): String =
    json.asString.getOrElse {
      json.asArray match {
        case Some(items) =>
          items.flatMap(_.asObject.flatMap(_("text")).flatMap(_.asString)).mkString(" ")
        case None => json.noSpaces
      }
    }

  private def errorResponse(statusCode: Int, body: Json, headers: Map[String, String] = Map.empty): IO[Response[IO]] = {
    val status = Status.fromInt(statusCode).getOrElse(Status.InternalServerError)
    withHeaders(Response[IO](status).withEntity(body).pure[IO], headers)
  }

  // Attaches a script's raw headers (see Step's headers field) to an
  // already-built response. A no-op when empty, so this is safe to wrap
  // every response-building call site with uniformly. Added one at a
  // time via putHeaders rather than spread as a Seq: http4s's implicit
  // Header.Raw -> Header.ToRaw conversion applies to individual varargs
  // arguments but not across a `: _*` spread, since the Seq's static
  // element type has to already be Header.ToRaw for that to type-check.
  private def withHeaders(response: IO[Response[IO]], headers: Map[String, String]): IO[Response[IO]] =
    if (headers.isEmpty) response
    else response.map { r =>
      headers.foldLeft(r) { case (resp, (k, v)) => resp.putHeaders(Header.Raw(CIString(k), v)) }
    }

  // A script-provided UsageOverride is used verbatim when present -- for
  // testing behavior at a specific token count precisely (a budget check,
  // a context-window boundary) rather than at whatever the heuristic
  // below happens to produce for that step's text. Word count is only a
  // stand-in default so a script that doesn't care still gets *something*
  // plausible to assert against, not a real tokenizer.
  private def fakeUsage(promptText: String, completionText: String, usageOverride: Option[UsageOverride]): OpenAI.Usage =
    usageOverride match {
      case Some(UsageOverride(promptTokens, completionTokens)) =>
        OpenAI.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
      case None =>
        val promptTokens     = promptText.split("\\s+").count(_.nonEmpty)
        val completionTokens = completionText.split("\\s+").count(_.nonEmpty)
        OpenAI.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
    }
}
