package com.alai.llmsim

import cats.effect.{Clock, IO}
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.Stream
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import io.circe.{Json, Printer}
import io.circe.parser.{parse => parseJson}
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.typelevel.ci.CIString
import scala.concurrent.duration.{Duration, FiniteDuration}

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

    // receivedAt/startedAt: real (wall-clock) time for timeline
    // timestamps, monotonic time for duration, per Cats Effect's own
    // distinction between the two -- real time can jump (NTP
    // adjustment, clock skew) and is wrong for measuring elapsed
    // duration, monotonic time can't be turned into a meaningful Unix
    // timestamp. Both are captured once, at the very top of each
    // route's for-comprehension, before request-body decoding even
    // happens.
    //
    // beginTimed/completeTimed replace what used to be a single
    // recordTimed call. journal.begin reserves a sequence number and
    // records arrival as soon as decode outcome is known (success or
    // failure) -- BEFORE runner.next or any response-building work
    // happens -- so sequence numbers reflect true arrival order even
    // if two concurrent requests finish in a different order than they
    // arrived. journal.complete records the actual outcome; for every
    // non-streaming branch that still runs immediately, right where
    // the old single recordTimed call used to sit. For a STREAMED
    // response, completeTimed is called from the frames stream's own
    // onFinalizeCase (see sseResponse's call sites below), so it fires
    // once the stream is actually done being consumed -- successfully,
    // with an error, or cancelled by a client disconnect -- not merely
    // when the response object is constructed. That distinction is
    // exactly what this split exists for: it was invisible with the
    // old single-shot design because nothing yet injects a delay long
    // enough for a client to disconnect mid-stream, but it stops being
    // theoretical the moment fault injection (roadmap item 14) adds
    // one.
    def beginTimed(
        provider: String,
        model: Option[String],
        messages: Vector[CapturedMessage],
        rawRequest: Json,
        receivedAt: FiniteDuration
    ): IO[CallHandle] =
      journal.begin(provider, model, messages, rawRequest, receivedAt.toMillis)

    def completeTimed(
        handle: CallHandle,
        outcome: CallOutcome,
        stepIndex: Option[Int],
        startedAt: FiniteDuration,
        responseHeaders: Map[String, String] = Map.empty,
        streamed: Boolean = false
    ): IO[CapturedCall] =
      for {
        finishedAt  <- Clock[IO].monotonic
        completedAt <- Clock[IO].realTime
        call        <- journal.complete(
                          handle, outcome, stepIndex, completedAt.toMillis, (finishedAt - startedAt).toMillis,
                          responseHeaders.map { case (k, v) => CapturedHeader(k, v) }.toVector,
                          streamed
                        )
      } yield call

    // Attaches journal completion to the frames stream's OWN lifecycle
    // via onFinalizeCase, rather than recording eagerly before the
    // response is even returned: this fires once the stream is
    // actually done being consumed, whether it finished normally,
    // errored, or was cancelled by a client disconnect partway through
    // -- see the comment on beginTimed/completeTimed above for why
    // that distinction matters. `Resource.ExitCase.Canceled` is the
    // client-disconnect case: nothing today can actually trigger it
    // (every chunk sends immediately, with no delay for a client to
    // disconnect during), but the wiring is correct and ready for
    // fault injection to exercise it.
    def finalizeStream(
        frames: Stream[IO, String],
        handle: CallHandle,
        outcome: CallOutcome,
        stepIndex: Option[Int],
        startedAt: FiniteDuration,
        headers: Map[String, String]
    ): Stream[IO, String] =
      frames.onFinalizeCase {
        case Resource.ExitCase.Succeeded =>
          completeTimed(handle, outcome, stepIndex, startedAt, headers, streamed = true).void
        case Resource.ExitCase.Errored(e) =>
          completeTimed(handle, CallOutcome.Failed(e.getMessage), stepIndex, startedAt, headers, streamed = true).void
        case Resource.ExitCase.Canceled =>
          completeTimed(
            handle, CallOutcome.Cancelled("client disconnected before the stream completed"),
            stepIndex, startedAt, headers, streamed = true
          ).void
      }

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
        handle <- beginTimed(provider, None, Vector.empty, json, receivedAt)
        _      <- completeTimed(handle, CallOutcome.Failed(message), None, startedAt)
        result <- errorResponse(400, errorJson)
      } yield result
    }

    HttpRoutes.of[IO] {

      // -----------------------------------------------------------------
      // GET /v1/models (roadmap item 15). Same path on both real vendors'
      // APIs (api.openai.com/v1/models, api.anthropic.com/v1/models) --
      // unlike chat/completions vs messages, there's no path to
      // disambiguate on here. Real clients tell them apart by which host
      // they're configured to hit; llmsim serves both from one host:port,
      // so it disambiguates the one way that's still genuinely accurate:
      // the Anthropic SDK always sends an `anthropic-version` header
      // (required on every real Anthropic API call), the OpenAI SDK
      // never does. Static and unscripted -- listing available models
      // isn't part of a conversation, so this doesn't touch the script
      // or the journal at all.
      // -----------------------------------------------------------------
      case req @ GET -> Root / "v1" / "models" =>
        if (req.headers.get(CIString("anthropic-version")).isDefined) {
          Ok(Anthropic.ModelList(data = List(
            Anthropic.ModelInfo(id = "claude-sonnet-5", display_name = "Claude Sonnet 5", created_at = "2026-01-01T00:00:00Z"),
            Anthropic.ModelInfo(id = "claude-haiku-4-5", display_name = "Claude Haiku 4.5", created_at = "2025-10-01T00:00:00Z")
          )).asJson)
        } else {
          Ok(OpenAI.ModelList(data = List(
            OpenAI.ModelInfo(id = "gpt-4o-mini", created = 1721692800L, owned_by = "openai"),
            OpenAI.ModelInfo(id = "gpt-4o", created = 1715558400L, owned_by = "openai")
          )).asJson)
        }

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
                          handle  <- beginTimed("openai", model, messages, json, receivedAt)
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers, streamFault), idx) if body.stream.contains(true) =>
                                         val chatId  = s"chatcmpl-sim-${UUID.randomUUID()}"
                                         val created = Instant.now().getEpochSecond
                                         def chunk(delta: OpenAI.Delta, finish: Option[String]): Json =
                                           OpenAI.ChatCompletionChunk(chatId, created = created, model = body.model,
                                             choices = List(OpenAI.ChunkChoice(0, delta, finish))).asJson

                                         val roleChunk     = chunk(OpenAI.Delta(role = Some("assistant")), None)
                                         val contentChunks = wordChunks(text).map(piece => chunk(OpenAI.Delta(content = Some(piece)), None))
                                         val finalChunk    = chunk(OpenAI.Delta(), Some("stop"))

                                         val renderedFrames = (roleChunk :: contentChunks ::: List(finalChunk))
                                           .map(j => sseFrame(None, j)) :+ "data: [DONE]\n\n"
                                         val frames = paced(renderedFrames, streamFault)

                                         // Same aggregate response shape a non-streaming call would have
                                         // logged -- only `streamed` distinguishes transport in the journal.
                                         val aggregate = OpenAI.ChatResponse(
                                           id = chatId, created = created, model = body.model,
                                           choices = List(OpenAI.Choice(0, OpenAI.Message(role = "assistant", content = Some(text)), "stop")),
                                           usage = fakeUsage(openAIPromptText(body), text, usageOverride)
                                         ).asJson

                                         sseResponse(
                                           finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                           headers
                                         )

                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers, _), idx) =>
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
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ToolCall(id, name, arguments, usageOverride, headers, streamFault), idx) if body.stream.contains(true) =>
                                         val chatId  = s"chatcmpl-sim-${UUID.randomUUID()}"
                                         val created = Instant.now().getEpochSecond
                                         def chunk(delta: OpenAI.Delta, finish: Option[String]): Json =
                                           OpenAI.ChatCompletionChunk(chatId, created = created, model = body.model,
                                             choices = List(OpenAI.ChunkChoice(0, delta, finish))).asJson

                                         val roleChunk = chunk(OpenAI.Delta(role = Some("assistant")), None)
                                         val toolChunk = chunk(
                                           OpenAI.Delta(tool_calls = Some(List(
                                             OpenAI.ChunkToolCall(index = 0, id = Some(id), `type` = Some("function"),
                                               function = Some(OpenAI.ChunkFunctionCall(Some(name), Some(arguments))))
                                           ))), None)
                                         val finalChunk = chunk(OpenAI.Delta(), Some("tool_calls"))

                                         val renderedFrames = List(roleChunk, toolChunk, finalChunk)
                                           .map(j => sseFrame(None, j)) :+ "data: [DONE]\n\n"
                                         val frames = paced(renderedFrames, streamFault)

                                         val aggregate = OpenAI.ChatResponse(
                                           id = chatId, created = created, model = body.model,
                                           choices = List(OpenAI.Choice(0,
                                             OpenAI.Message(role = "assistant", content = None,
                                               tool_calls = Some(List(OpenAI.ToolCall(id, "function", OpenAI.FunctionCall(name, arguments))))),
                                             "tool_calls")),
                                           usage = fakeUsage(openAIPromptText(body), s"$name($arguments)", usageOverride)
                                         ).asJson

                                         sseResponse(
                                           finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                           headers
                                         )

                                       case NextStep.Answer(Step.ToolCall(id, name, arguments, usageOverride, headers, _), idx) =>
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
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ReplyFromToolResult(toolCallId, render, usageOverride, headers, streamFault), idx) =>
                                         findOpenAIToolResult(body, toolCallId) match {
                                           case None =>
                                             val message = s"llmsim: this script step expected a tool_result for " +
                                               s"tool_call_id '$toolCallId', but none was found in the request."
                                             val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message, "missing_tool_result")).asJson
                                             for {
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Failed(message), Some(idx), startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Some(resultText) if body.stream.contains(true) =>
                                             val text    = render(resultText)
                                             val chatId  = s"chatcmpl-sim-${UUID.randomUUID()}"
                                             val created = Instant.now().getEpochSecond
                                             def chunk(delta: OpenAI.Delta, finish: Option[String]): Json =
                                               OpenAI.ChatCompletionChunk(chatId, created = created, model = body.model,
                                                 choices = List(OpenAI.ChunkChoice(0, delta, finish))).asJson

                                             val roleChunk     = chunk(OpenAI.Delta(role = Some("assistant")), None)
                                             val contentChunks = wordChunks(text).map(piece => chunk(OpenAI.Delta(content = Some(piece)), None))
                                             val finalChunk    = chunk(OpenAI.Delta(), Some("stop"))

                                             val renderedFrames = (roleChunk :: contentChunks ::: List(finalChunk))
                                               .map(j => sseFrame(None, j)) :+ "data: [DONE]\n\n"
                                             val frames = paced(renderedFrames, streamFault)

                                             val aggregate = OpenAI.ChatResponse(
                                               id = chatId, created = created, model = body.model,
                                               choices = List(OpenAI.Choice(0, OpenAI.Message(role = "assistant", content = Some(text)), "stop")),
                                               usage = fakeUsage(openAIPromptText(body), text, usageOverride)
                                             ).asJson

                                             sseResponse(
                                               finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                               headers
                                             )

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
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.Error(status, message, headers), idx) =>
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message)).asJson
                                         for {
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Rejected(status, message), Some(idx), startedAt, headers)
                                           result <- errorResponse(status, errorJson, headers)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = OpenAI.ErrorBody(OpenAI.ErrorDetail(message, "script_exhausted")).asJson
                                         for {
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Rejected(500, message), None, startedAt)
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
                          handle  <- beginTimed("anthropic", model, messages, json, receivedAt)
                          outcome <- runner.next
                          result  <- outcome match {
                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers, streamFault), idx) if body.stream.contains(true) =>
                                         val msgId = s"msg-sim-${UUID.randomUUID()}"
                                         val usage = anthropicUsage(anthropicPromptText(body), text, usageOverride)

                                         val messageStart = Anthropic.MessageStartPayload(
                                           message = Anthropic.MessageStartMessage(
                                             id = msgId, model = body.model, stop_reason = None,
                                             usage = Anthropic.StreamUsage(Some(usage.input_tokens), Some(0))
                                           )
                                         ).asJson
                                         val blockStart = Anthropic.ContentBlockStartPayload(
                                           index = 0, content_block = Anthropic.ContentBlock(`type` = "text", text = Some(""))
                                         ).asJson
                                         val textDeltas = wordChunks(text).map(piece =>
                                           Anthropic.ContentBlockDeltaPayload(index = 0, delta = Anthropic.TextDelta(text = piece).asJson).asJson)
                                         val blockStop = Anthropic.ContentBlockStopPayload(index = 0).asJson
                                         val messageDelta = Anthropic.MessageDeltaPayload(
                                           delta = Anthropic.MessageDeltaInner(stop_reason = "end_turn"),
                                           usage = Anthropic.StreamUsage(None, Some(usage.output_tokens))
                                         ).asJson
                                         val messageStop = Anthropic.MessageStopPayload().asJson

                                         val events: List[(String, Json)] =
                                           ("message_start", messageStart) :: ("content_block_start", blockStart) ::
                                             textDeltas.map(d => ("content_block_delta", d)) :::
                                             List(("content_block_stop", blockStop), ("message_delta", messageDelta), ("message_stop", messageStop))

                                         val renderedFrames = events.map { case (ev, j) => sseFrame(Some(ev), j) }
                                         val frames = paced(renderedFrames, streamFault)

                                         val aggregate = Anthropic.MessagesResponse(
                                           id = msgId, content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                           model = body.model, stop_reason = "end_turn", usage = usage
                                         ).asJson

                                         sseResponse(
                                           finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                           headers
                                         )

                                       case NextStep.Answer(Step.Reply(text, usageOverride, headers, _), idx) =>
                                         val response = Anthropic.MessagesResponse(
                                           id = s"msg-sim-${UUID.randomUUID()}",
                                           content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                           model = body.model,
                                           stop_reason = "end_turn",
                                           usage = anthropicUsage(anthropicPromptText(body), text, usageOverride)
                                         )
                                         val responseJson = response.asJson
                                         for {
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                           result <- withHeaders(Ok(responseJson), headers)
                                         } yield result

                                       case NextStep.Answer(Step.ToolCall(id, name, arguments, usageOverride, headers, streamFault), idx) =>
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
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Failed(message), Some(idx), startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Right(inputJson) if body.stream.contains(true) =>
                                             val msgId = s"msg-sim-${UUID.randomUUID()}"
                                             val usage = anthropicUsage(anthropicPromptText(body), s"$name(${inputJson.noSpaces})", usageOverride)

                                             val messageStart = Anthropic.MessageStartPayload(
                                               message = Anthropic.MessageStartMessage(
                                                 id = msgId, model = body.model, stop_reason = None,
                                                 usage = Anthropic.StreamUsage(Some(usage.input_tokens), Some(0))
                                               )
                                             ).asJson
                                             val blockStart = Anthropic.ContentBlockStartPayload(
                                               index = 0,
                                               content_block = Anthropic.ContentBlock(`type` = "tool_use", id = Some(id), name = Some(name), input = Some(Json.obj()))
                                             ).asJson
                                             val inputDelta = Anthropic.ContentBlockDeltaPayload(
                                               index = 0, delta = Anthropic.InputJsonDelta(partial_json = inputJson.noSpaces).asJson
                                             ).asJson
                                             val blockStop = Anthropic.ContentBlockStopPayload(index = 0).asJson
                                             val messageDelta = Anthropic.MessageDeltaPayload(
                                               delta = Anthropic.MessageDeltaInner(stop_reason = "tool_use"),
                                               usage = Anthropic.StreamUsage(None, Some(usage.output_tokens))
                                             ).asJson
                                             val messageStop = Anthropic.MessageStopPayload().asJson

                                             val events: List[(String, Json)] = List(
                                               ("message_start", messageStart), ("content_block_start", blockStart),
                                               ("content_block_delta", inputDelta), ("content_block_stop", blockStop),
                                               ("message_delta", messageDelta), ("message_stop", messageStop)
                                             )
                                             val renderedFrames = events.map { case (ev, j) => sseFrame(Some(ev), j) }
                                             val frames = paced(renderedFrames, streamFault)

                                             val aggregate = Anthropic.MessagesResponse(
                                               id = msgId,
                                               content = List(Anthropic.ContentBlock(`type` = "tool_use", id = Some(id), name = Some(name), input = Some(inputJson))),
                                               model = body.model, stop_reason = "tool_use", usage = usage
                                             ).asJson

                                             sseResponse(
                                               finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                               headers
                                             )

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
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.ReplyFromToolResult(toolCallId, render, usageOverride, headers, streamFault), idx) =>
                                         findAnthropicToolResult(body, toolCallId) match {
                                           case None =>
                                             val message = s"llmsim: this script step expected a tool_result for " +
                                               s"tool_use_id '$toolCallId', but none was found in the request."
                                             val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("missing_tool_result", message)).asJson
                                             for {
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Failed(message), Some(idx), startedAt)
                                               result <- errorResponse(500, errorJson)
                                             } yield result

                                           case Some(resultText) if body.stream.contains(true) =>
                                             val text  = render(resultText)
                                             val msgId = s"msg-sim-${UUID.randomUUID()}"
                                             val usage = anthropicUsage(anthropicPromptText(body), text, usageOverride)

                                             val messageStart = Anthropic.MessageStartPayload(
                                               message = Anthropic.MessageStartMessage(
                                                 id = msgId, model = body.model, stop_reason = None,
                                                 usage = Anthropic.StreamUsage(Some(usage.input_tokens), Some(0))
                                               )
                                             ).asJson
                                             val blockStart = Anthropic.ContentBlockStartPayload(
                                               index = 0, content_block = Anthropic.ContentBlock(`type` = "text", text = Some(""))
                                             ).asJson
                                             val textDeltas = wordChunks(text).map(piece =>
                                               Anthropic.ContentBlockDeltaPayload(index = 0, delta = Anthropic.TextDelta(text = piece).asJson).asJson)
                                             val blockStop = Anthropic.ContentBlockStopPayload(index = 0).asJson
                                             val messageDelta = Anthropic.MessageDeltaPayload(
                                               delta = Anthropic.MessageDeltaInner(stop_reason = "end_turn"),
                                               usage = Anthropic.StreamUsage(None, Some(usage.output_tokens))
                                             ).asJson
                                             val messageStop = Anthropic.MessageStopPayload().asJson

                                             val events: List[(String, Json)] =
                                               ("message_start", messageStart) :: ("content_block_start", blockStart) ::
                                                 textDeltas.map(d => ("content_block_delta", d)) :::
                                                 List(("content_block_stop", blockStop), ("message_delta", messageDelta), ("message_stop", messageStop))

                                             val renderedFrames = events.map { case (ev, j) => sseFrame(Some(ev), j) }
                                             val frames = paced(renderedFrames, streamFault)

                                             val aggregate = Anthropic.MessagesResponse(
                                               id = msgId, content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                                               model = body.model, stop_reason = "end_turn", usage = usage
                                             ).asJson

                                             sseResponse(
                                               finalizeStream(frames, handle, CallOutcome.Responded(200, aggregate), Some(idx), startedAt, headers),
                                               headers
                                             )

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
                                               _      <- completeTimed(handle,
                                                            CallOutcome.Responded(200, responseJson), Some(idx), startedAt, headers)
                                               result <- withHeaders(Ok(responseJson), headers)
                                             } yield result
                                         }

                                       case NextStep.Answer(Step.Error(status, message, headers), idx) =>
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("simulated_error", message)).asJson
                                         for {
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Rejected(status, message), Some(idx), startedAt, headers)
                                           result <- errorResponse(status, errorJson, headers)
                                         } yield result

                                       case NextStep.Exhausted =>
                                         val message = "llmsim: script exhausted -- simulator received a call beyond the configured script"
                                         val errorJson = Anthropic.ErrorBody(error = Anthropic.ErrorDetail("script_exhausted", message)).asJson
                                         for {
                                           _      <- completeTimed(handle,
                                                        CallOutcome.Rejected(500, message), None, startedAt)
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

  // ---------------------------------------------------------------------
  // SSE plumbing. Built by hand (raw "event: ...\ndata: ...\n\n" strings)
  // rather than through http4s's ServerSentEvent helper type, so this
  // doesn't depend on exactly which package that type/encoder lives
  // under in this http4s version.
  //
  // Content-Type, Cache-Control, and X-Accel-Buffering are set
  // explicitly: the last one is a defensive no-op against llmsim's own
  // server (Ember doesn't buffer SSE responses), but matters the moment
  // llmsim sits behind an Nginx-shaped proxy in someone's test
  // environment.
  // ---------------------------------------------------------------------

  // Explicit nulls dropped here, not just in the streaming shapes'
  // definitions: real OpenAI/Anthropic chunks OMIT an absent optional
  // field entirely (e.g. a text delta has no `tool_calls` key at all)
  // rather than sending it as `null`, which circe's derived encoders do
  // by default. One conversion point for every streaming payload is
  // simpler and lower-risk than hand-writing a narrower encoder per
  // shape, and -- deliberately -- this only touches SSE frames, not the
  // non-streaming JSON bodies, which keep their existing (already
  // documented, already tested) explicit-null shape.
  private val dropNullPrinter = Printer.noSpaces.copy(dropNullValues = true)

  private def sseFrame(event: Option[String], data: Json): String = {
    val eventLine = event.map(e => s"event: $e\n").getOrElse("")
    s"$eventLine" + s"data: ${dropNullPrinter.print(data)}\n\n"
  }

  private def sseResponse(frames: Stream[IO, String], headers: Map[String, String]): IO[Response[IO]] =
    withHeaders(
      Response[IO](Status.Ok)
        .putHeaders(
          Header.Raw(CIString("Content-Type"), "text/event-stream; charset=utf-8"),
          Header.Raw(CIString("Cache-Control"), "no-cache"),
          Header.Raw(CIString("X-Accel-Buffering"), "no")
        )
        .withBodyStream(frames.through(fs2.text.utf8.encode))
        .pure[IO],
      headers
    )

  // A chunk-by-word split is a stand-in for real token boundaries --
  // good enough to exercise a client's incremental-append handling
  // without needing a real tokenizer. Fault injection (roadmap item 14)
  // is where finer-grained or deliberately-odd chunking becomes a
  // script's explicit choice; MVP always sends one complete word per
  // chunk.
  //
  // split(" ", -1) deliberately, not split(" "): the no-limit-argument
  // form of split silently drops TRAILING empty strings, which for this
  // purpose means the reply "hello " would stream as "hello" (its
  // trailing space lost) and an all-whitespace reply like "   " would
  // stream as "" (nothing at all) -- streaming and non-streaming
  // transports returning different content for the identical scripted
  // string, which is exactly the invariant this whole design exists to
  // preserve. The -1 limit keeps every trailing empty piece so the
  // reconstructed stream matches the scripted text exactly, whitespace
  // included.
  private def wordChunks(text: String): List[String] =
    text.split(" ", -1).toList.zipWithIndex.map { case (w, i) => if (i == 0) w else s" $w" }

  // Turns a list of already-rendered SSE frames (post-sseFrame, so
  // including OpenAI's literal "data: [DONE]\n\n" where it applies) into
  // a Stream, inserting artificial delays per a script's StreamFault --
  // delayBeforeFirstEvent before the first frame, delayBetweenEvents
  // before every frame after that (including the terminal one). A
  // zero duration (StreamFault's default) is filtered out here rather
  // than turned into a Stream.sleep(Duration.Zero) -- same observable
  // result, but skips spurious IO scheduling overhead on the common
  // no-delay path. Plain Stream.emits(frames) when fault is None or
  // specifies no delays at all -- the non-faulted path this project
  // has relied on since SSE first shipped is untouched.
  //
  // Any delay longer than heartbeatInterval gets broken into shorter
  // chunks punctuated by an SSE comment line (HeartbeatFrame) -- see
  // StreamFault's own doc comment for why: a confirmed finding, not a
  // guess, that a real disconnect isn't noticed until the next write
  // attempt, so breaking a long silence into periodic writes is what
  // actually bounds discovery latency, rather than working around a
  // gap that turned out not to be fixable at a lower level.
  //
  // private[llmsim], not private: the isolated cancellation test (see
  // DisconnectSpec.scala) calls this directly, to verify the delay
  // mechanism itself cooperates with cancellation independent of any
  // HTTP plumbing -- see docs/ for the design note this responds to.
  // Still not part of the public API a script author would ever touch.
  private val HeartbeatFrame = ": heartbeat\n\n"

  private def pacedDelay(d: FiniteDuration, heartbeatInterval: FiniteDuration): Stream[IO, String] =
    if (d <= Duration.Zero) {
      Stream.empty
    } else if (heartbeatInterval <= Duration.Zero || d <= heartbeatInterval) {
      Stream.sleep[IO](d).drain
    } else {
      val fullBeats = (d.toMillis / heartbeatInterval.toMillis).toInt
      val remainder = d - heartbeatInterval * fullBeats.toLong
      val beats = List.fill(fullBeats)(()).foldLeft(Stream.empty: Stream[IO, String]) { (acc, _) =>
        acc ++ Stream.sleep[IO](heartbeatInterval).drain ++ Stream.emit(HeartbeatFrame)
      }
      beats ++ Stream.sleep[IO](remainder).drain
    }

  private[llmsim] def paced(frames: List[String], fault: Option[StreamFault]): Stream[IO, String] = {
    val heartbeatInterval = fault.map(_.heartbeatInterval).getOrElse(Duration.Zero)
    val beforeFirst = fault.map(_.delayBeforeFirstEvent).filter(_ > Duration.Zero)
    val between     = fault.map(_.delayBetweenEvents).filter(_ > Duration.Zero)
    frames.zipWithIndex.foldLeft(Stream.empty: Stream[IO, String]) { case (acc, (frame, i)) =>
      val delay = if (i == 0) beforeFirst else between
      val delayed = delay.map(d => pacedDelay(d, heartbeatInterval)).getOrElse(Stream.empty: Stream[IO, String])
      acc ++ delayed ++ Stream.emit(frame)
    }
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
