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
      max_tokens: Option[Int] = None,
      stream: Option[Boolean] = None
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

  /** Streaming (SSE) chunk shapes -- one JSON object per `data:` line,
    * matching OpenAI's `chat.completion.chunk` wire format.
    *
    * MVP: each chunk carries a COMPLETE unit (a whole word of content,
    * or a whole tool call's name+arguments) rather than splitting a
    * single word or a tool call's arguments across many chunks the way
    * a real model sometimes does -- that finer-grained splitting is
    * deliberately deferred to fault injection (roadmap item 14, "tool
    * call arguments split across chunks"), where it's something a
    * script opts into, not the default streaming behavior. Scripted
    * `usage` is also not reflected in streaming responses yet -- real
    * OpenAI only includes usage in a stream when the request sets
    * `stream_options: {include_usage: true}`, which llmsim doesn't yet
    * read; deferred, not forgotten.
    */
  final case class ChunkFunctionCall(name: Option[String] = None, arguments: Option[String] = None)
  final case class ChunkToolCall(
      index: Int,
      id: Option[String] = None,
      `type`: Option[String] = None,
      function: Option[ChunkFunctionCall] = None
  )
  final case class Delta(
      role: Option[String] = None,
      content: Option[String] = None,
      tool_calls: Option[List[ChunkToolCall]] = None
  )
  final case class ChunkChoice(index: Int, delta: Delta, finish_reason: Option[String] = None)
  final case class ChatCompletionChunk(
      id: String,
      `object`: String = "chat.completion.chunk",
      created: Long,
      model: String,
      choices: List[ChunkChoice]
  )

  implicit val functionCallCodec: Codec[FunctionCall]   = deriveCodec
  implicit val toolCallCodec: Codec[ToolCall]           = deriveCodec
  implicit val choiceCodec: Codec[Choice]               = deriveCodec
  implicit val usageCodec: Codec[Usage]                 = deriveCodec
  implicit val chatRequestCodec: Codec[ChatRequest]     = deriveCodec
  implicit val chatResponseCodec: Codec[ChatResponse]   = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]     = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]         = deriveCodec
  implicit val chunkFunctionCallCodec: Codec[ChunkFunctionCall]     = deriveCodec
  implicit val chunkToolCallCodec: Codec[ChunkToolCall]             = deriveCodec
  implicit val deltaCodec: Codec[Delta]                             = deriveCodec
  implicit val chunkChoiceCodec: Codec[ChunkChoice]                 = deriveCodec
  implicit val chatCompletionChunkCodec: Codec[ChatCompletionChunk] = deriveCodec
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
      system: Option[String] = None,
      stream: Option[Boolean] = None
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

  /** Streaming (SSE) event payloads -- each is the payload of one named
    * `event:` / `data:` pair in Anthropic's wire format. A dedicated
    * `MessageStartMessage` (rather than reusing `MessagesResponse`) is
    * used for `message_start` because the real event's `stop_reason` is
    * `null` at that point, and `MessagesResponse.stop_reason` is a
    * required String on the non-streaming response -- this avoids
    * loosening that shape just to accommodate streaming's partial
    * state.
    *
    * MVP: a tool_use block's full `input` is emitted as a single
    * `input_json_delta` rather than split across several partial JSON
    * fragments -- see the equivalent OpenAI-side comment in that
    * object; same deferral to fault injection applies here.
    */
  final case class StreamUsage(input_tokens: Option[Int] = None, output_tokens: Option[Int] = None)
  final case class MessageStartMessage(
      id: String,
      `type`: String = "message",
      role: String = "assistant",
      content: List[ContentBlock] = Nil,
      model: String,
      stop_reason: Option[String] = None,
      usage: StreamUsage
  )
  final case class MessageStartPayload(`type`: String = "message_start", message: MessageStartMessage)
  final case class ContentBlockStartPayload(`type`: String = "content_block_start", index: Int, content_block: ContentBlock)
  final case class TextDelta(`type`: String = "text_delta", text: String)
  final case class InputJsonDelta(`type`: String = "input_json_delta", partial_json: String)
  // Both delta shapes above share a `type` discriminator on the wire,
  // but each only ever appears standalone inside a
  // ContentBlockDeltaPayload built by the simulator -- never decoded
  // back on llmsim's side -- so `delta` is plain Json here rather than
  // a sealed trait Codec.
  final case class ContentBlockDeltaPayload(`type`: String = "content_block_delta", index: Int, delta: Json)
  final case class ContentBlockStopPayload(`type`: String = "content_block_stop", index: Int)
  final case class MessageDeltaInner(stop_reason: String, stop_sequence: Option[String] = None)
  final case class MessageDeltaPayload(`type`: String = "message_delta", delta: MessageDeltaInner, usage: StreamUsage)
  final case class MessageStopPayload(`type`: String = "message_stop")

  implicit val contentBlockCodec: Codec[ContentBlock]         = deriveCodec
  implicit val usageCodec: Codec[Usage]                       = deriveCodec
  implicit val messagesRequestCodec: Codec[MessagesRequest]   = deriveCodec
  implicit val messagesResponseCodec: Codec[MessagesResponse] = deriveCodec
  implicit val errorDetailCodec: Codec[ErrorDetail]           = deriveCodec
  implicit val errorBodyCodec: Codec[ErrorBody]               = deriveCodec
  implicit val streamUsageCodec: Codec[StreamUsage]                           = deriveCodec
  implicit val messageStartMessageCodec: Codec[MessageStartMessage]           = deriveCodec
  implicit val messageStartPayloadCodec: Codec[MessageStartPayload]           = deriveCodec
  implicit val contentBlockStartPayloadCodec: Codec[ContentBlockStartPayload] = deriveCodec
  implicit val textDeltaCodec: Codec[TextDelta]                               = deriveCodec
  implicit val inputJsonDeltaCodec: Codec[InputJsonDelta]                     = deriveCodec
  implicit val contentBlockDeltaPayloadCodec: Codec[ContentBlockDeltaPayload] = deriveCodec
  implicit val contentBlockStopPayloadCodec: Codec[ContentBlockStopPayload]   = deriveCodec
  implicit val messageDeltaInnerCodec: Codec[MessageDeltaInner]               = deriveCodec
  implicit val messageDeltaPayloadCodec: Codec[MessageDeltaPayload]           = deriveCodec
  implicit val messageStopPayloadCodec: Codec[MessageStopPayload]             = deriveCodec
}
