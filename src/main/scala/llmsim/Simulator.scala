package llmsim

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
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
  */
object Simulator {

  private implicit val openAIRequestDecoder: EntityDecoder[IO, OpenAI.ChatRequest] =
    jsonOf[IO, OpenAI.ChatRequest]
  private implicit val openAIResponseEncoder: EntityEncoder[IO, OpenAI.ChatResponse] =
    jsonEncoderOf[IO, OpenAI.ChatResponse]
  private implicit val openAIErrorEncoder: EntityEncoder[IO, OpenAI.ErrorBody] =
    jsonEncoderOf[IO, OpenAI.ErrorBody]

  private implicit val anthropicRequestDecoder: EntityDecoder[IO, Anthropic.MessagesRequest] =
    jsonOf[IO, Anthropic.MessagesRequest]
  private implicit val anthropicResponseEncoder: EntityEncoder[IO, Anthropic.MessagesResponse] =
    jsonEncoderOf[IO, Anthropic.MessagesResponse]
  private implicit val anthropicErrorEncoder: EntityEncoder[IO, Anthropic.ErrorBody] =
    jsonEncoderOf[IO, Anthropic.ErrorBody]

  def routes(runner: ScriptRunner): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      // -----------------------------------------------------------------
      // OpenAI-shaped endpoint
      // -----------------------------------------------------------------
      case req @ POST -> Root / "v1" / "chat" / "completions" =>
        for {
          body    <- req.as[OpenAI.ChatRequest]
          outcome <- runner.next
          result  <- outcome match {
                       case NextStep.Answer(Step.Reply(text)) =>
                         Ok(
                           OpenAI.ChatResponse(
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
                         )

                       case NextStep.Answer(Step.Error(status, message)) =>
                         errorResponse(status, OpenAI.ErrorBody(OpenAI.ErrorDetail(message)))

                       case NextStep.Exhausted =>
                         errorResponse(
                           500,
                           OpenAI.ErrorBody(
                             OpenAI.ErrorDetail(
                               "llmsim: script exhausted -- simulator received a call beyond the configured script",
                               "script_exhausted"
                             )
                           )
                         )
                     }
        } yield result

      // -----------------------------------------------------------------
      // Anthropic-shaped endpoint
      // -----------------------------------------------------------------
      case req @ POST -> Root / "v1" / "messages" =>
        for {
          body    <- req.as[Anthropic.MessagesRequest]
          outcome <- runner.next
          result  <- outcome match {
                       case NextStep.Answer(Step.Reply(text)) =>
                         Ok(
                           Anthropic.MessagesResponse(
                             id = s"msg-sim-${UUID.randomUUID()}",
                             content = List(Anthropic.ContentBlock(`type` = "text", text = Some(text))),
                             model = body.model,
                             stop_reason = "end_turn",
                             usage = {
                               val u = fakeUsage(body.messages.flatMap(_.content.map(_.text)).mkString(" "), text)
                               Anthropic.Usage(input_tokens = u.prompt_tokens, output_tokens = u.completion_tokens)
                             }
                           )
                         )

                       case NextStep.Answer(Step.Error(status, message)) =>
                         errorResponse(status, Anthropic.ErrorBody(error = Anthropic.ErrorDetail("simulated_error", message)))

                       case NextStep.Exhausted =>
                         errorResponse(
                           500,
                           Anthropic.ErrorBody(
                             error = Anthropic.ErrorDetail(
                               "script_exhausted",
                               "llmsim: script exhausted -- simulator received a call beyond the configured script"
                             )
                           )
                         )
                     }
        } yield result
    }

  private def errorResponse[A](statusCode: Int, body: A)(implicit enc: EntityEncoder[IO, A]): IO[Response[IO]] = {
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
