package com.alai.llmsim

import io.circe.Codec
import io.circe.generic.semiauto._

/** Rung 1 of the ladder: just enough of each vendor's wire shape to
  * represent a single, non-streaming request/response round trip. No
  * tool_use, no streaming deltas yet — those come later.
  *
  * `OpenAI` and `Anthropic` are top-level objects directly in this
  * package, NOT nested inside a wrapping `Protocol` object. Two earlier
  * attempts nested them one level deeper (`object Protocol { object OpenAI ... }`)
  * and required a wildcard `import Protocol._` in every consuming file;
  * that combination triggered a "not found: OpenAI" failure in this
  * environment's Scala 3 / circe-macro setup. Flattening sidesteps it
  * and has a nice side effect: every file in this package sees
  * `OpenAI` and `Anthropic` automatically, no import required at all.
  *
  * Codecs live in the same object as the case classes they encode, so
  * Scala's implicit search finds them via the object's own scope with
  * no additional import.
  */
object OpenAI {
  final case class Message(role: String, content: String)

  final case class ChatRequest(
      model: String,
      messages: List[Message],
      temperature: Option[Double] = None,
      max_tokens: Option[Int] = None
  )

  final case class Choice(
      index: Int,
      message: Message,
      finish_reason: String
  )

  final case class Usage(
      prompt_tokens: Int,
      completion_tokens: Int,
      total_tokens: Int
  )

  final case class ChatResponse(
      id: String,
      `object`: String = "chat.completion",
      created: Long,
      model: String,
      choices: List[Choice],
      usage: Usage
  )

  final case class ErrorDetail(message: String, `type`: String = "simulated_error")
  final case class ErrorBody(error: ErrorDetail)

  implicit val messageCodec: Codec[Message]           = deriveCodec
  implicit val choiceCodec: Codec[Choice]             = deriveCodec
  implicit val usageCodec: Codec[Usage]               = deriveCodec
  implicit val chatRequestCodec: Codec[ChatRequest]   = deriveCodec
  implicit val chatResponseCodec: Codec[ChatResponse] = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]   = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]       = deriveCodec
}

object Anthropic {
  final case class ContentBlock(`type`: String, text: Option[String] = None)

  final case class Message(role: String, content: List[ContentBlock])

  final case class MessagesRequest(
      model: String,
      max_tokens: Int,
      messages: List[Message],
      system: Option[String] = None
  )

  final case class Usage(input_tokens: Int, output_tokens: Int)

  final case class MessagesResponse(
      id: String,
      `type`: String = "message",
      role: String = "assistant",
      content: List[ContentBlock],
      model: String,
      stop_reason: String,
      usage: Usage
  )

  final case class ErrorDetail(`type`: String, message: String)
  final case class ErrorBody(`type`: String = "error", error: ErrorDetail)

  implicit val contentBlockCodec: Codec[ContentBlock]         = deriveCodec
  implicit val messageCodec: Codec[Message]                   = deriveCodec
  implicit val usageCodec: Codec[Usage]                       = deriveCodec
  implicit val messagesRequestCodec: Codec[MessagesRequest]   = deriveCodec
  implicit val messagesResponseCodec: Codec[MessagesResponse] = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]           = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]               = deriveCodec
}
