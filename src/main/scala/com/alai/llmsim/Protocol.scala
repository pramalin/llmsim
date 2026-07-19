package com.alai.llmsim

import io.circe.{Codec, Json}
import io.circe.generic.semiauto._

/** Just enough of each vendor's wire shape to represent a request/response
  * round trip, including tool_use/tool_call round trips. No streaming yet.
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

  /** A tool call's `arguments` is deliberately a raw String, matching
    * OpenAI's actual wire type exactly -- it's a JSON-ENCODED STRING, not
    * a nested object. We never validate that it parses. That's not an
    * oversight: it's what lets a script simulate a model that emitted
    * malformed tool-call arguments, which is a real thing models
    * occasionally do, without needing any separate "malformed JSON"
    * feature -- an invalid-JSON string is just an ordinary String.
    */
  final case class FunctionCall(name: String, arguments: String)
  final case class ToolCall(id: String, `type`: String = "function", function: FunctionCall)

  final case class Message(
      role: String,
      content: Option[String] = None,
      tool_calls: Option[List[ToolCall]] = None,
      // Set on a "tool" role message: which tool_call this is the result of.
      tool_call_id: Option[String] = None
  )

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

  implicit val functionCallCodec: Codec[FunctionCall]   = deriveCodec
  implicit val toolCallCodec: Codec[ToolCall]           = deriveCodec
  implicit val messageCodec: Codec[Message]             = deriveCodec
  implicit val choiceCodec: Codec[Choice]               = deriveCodec
  implicit val usageCodec: Codec[Usage]                 = deriveCodec
  implicit val chatRequestCodec: Codec[ChatRequest]     = deriveCodec
  implicit val chatResponseCodec: Codec[ChatResponse]   = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]     = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]         = deriveCodec
}

object Anthropic {

  /** Unlike OpenAI, Anthropic's tool_use `input` is a real nested JSON
    * object at the wire level (not a string), and a tool_result's
    * `content` can be a plain string or structured content -- so both
    * are modeled as `Json` here rather than String. This is also why a
    * "malformed JSON tool arguments" test isn't representable against
    * this endpoint: `input` has to be valid JSON for the surrounding
    * response body to be valid JSON at all. See Simulator.scala for how
    * that case is handled (it fails loudly rather than silently coercing
    * something misleading).
    */
  final case class ContentBlock(
      `type`: String,
      text: Option[String] = None,
      // tool_use fields
      id: Option[String] = None,
      name: Option[String] = None,
      input: Option[Json] = None,
      // tool_result fields
      tool_use_id: Option[String] = None,
      content: Option[Json] = None
  )

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
