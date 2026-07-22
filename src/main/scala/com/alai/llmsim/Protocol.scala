package com.alai.llmsim

import io.circe.{Codec, Decoder, Encoder, Json}
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

  /** `content` accepts either a plain JSON string or an array of content
    * parts (OpenAI's real API supports both -- the array form is how
    * multimodal messages work). Only the decoder needs to be lenient;
    * llmsim only ever decodes Message from incoming requests, it never
    * encodes one back out (ChatResponse.choices carries its own
    * Message, encoded via the plain derived codec below, always as a
    * plain string since llmsim never sends multimodal content).
    */
  final case class Message(
      role: String,
      content: Option[String] = None,
      tool_calls: Option[List[ToolCall]] = None,
      // Set on a "tool" role message: which tool_call this is the result of.
      tool_call_id: Option[String] = None
  )
  object Message {
    // An array-of-parts content is flattened to its text parts joined by
    // a space -- enough to exercise a script's Reply/ToolCall logic,
    // which only ever reads message text, never structured parts. Joined
    // WITH a separator deliberately: mkString with no argument
    // concatenates with none at all, so ["hello", "world"] would become
    // "helloworld" rather than "hello world", corrupting both the
    // normalized journal content and the fallback token-count heuristic.
    private val arrayContentAsString: Decoder[Option[String]] =
      Decoder[List[Json]].map { parts =>
        val text = parts.flatMap(_.asObject).flatMap(_("text")).flatMap(_.asString).mkString(" ")
        if (text.isEmpty) None else Some(text)
      }

    implicit val decoder: Decoder[Message] = Decoder.instance { cursor =>
      for {
        role         <- cursor.downField("role").as[String]
        content      <- cursor.downField("content").as[Option[String]]
                           .orElse(cursor.downField("content").as[Option[String]](arrayContentAsString))
        toolCalls    <- cursor.downField("tool_calls").as[Option[List[ToolCall]]]
        toolCallId   <- cursor.downField("tool_call_id").as[Option[String]]
      } yield Message(role, content, toolCalls, toolCallId)
    }
    implicit val encoder: Encoder[Message] = deriveEncoder
  }

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

  /** `content` accepts either a plain JSON string -- Anthropic's real API
    * shorthand for a single text block, used by Spring AI 2.0's official
    * Anthropic SDK when a caller does `ChatClient.prompt("hello")` -- or
    * the full array-of-content-blocks form. llmsim only ever decodes
    * Message (it's never part of an outgoing response body), so only
    * the decoder needs to be lenient; the encoder stays derived normally
    * for tests that construct request bodies directly.
    */
  final case class Message(role: String, content: List[ContentBlock])
  object Message {
    private val arrayContent: Decoder[List[ContentBlock]] =
      Decoder[List[ContentBlock]]
    private val stringContent: Decoder[List[ContentBlock]] =
      Decoder[String].map(text => List(ContentBlock(`type` = "text", text = Some(text))))

    implicit val decoder: Decoder[Message] = Decoder.instance { cursor =>
      for {
        role    <- cursor.downField("role").as[String]
        content <- cursor.downField("content").as[List[ContentBlock]](arrayContent)
                     .orElse(cursor.downField("content").as[List[ContentBlock]](stringContent))
      } yield Message(role, content)
    }
    implicit val encoder: Encoder[Message] = deriveEncoder
  }

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
  implicit val usageCodec: Codec[Usage]                       = deriveCodec
  implicit val messagesRequestCodec: Codec[MessagesRequest]   = deriveCodec
  implicit val messagesResponseCodec: Codec[MessagesResponse] = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]           = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]               = deriveCodec
}
