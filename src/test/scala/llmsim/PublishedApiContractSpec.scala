package llmsim

import io.circe.parser.decode
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Checks Protocol.scala's case classes against EXAMPLE response payloads
  * shaped like each vendor's own published API reference docs -- not live
  * calls. No network access or API keys needed; runs every time as part
  * of `sbt test`.
  *
  * A failure here means our case classes have drifted from what's
  * documented -- not necessarily from what's live right now. If a vendor
  * changes their published schema, update the JSON below to match.
  */
class PublishedApiContractSpec extends AnyFreeSpec with Matchers {

  "OpenAI's documented chat completion response shape" - {
    val json =
      """{
        |  "id": "chatcmpl-abc123",
        |  "object": "chat.completion",
        |  "created": 1700000000,
        |  "model": "gpt-4o-mini",
        |  "choices": [
        |    {
        |      "index": 0,
        |      "message": { "role": "assistant", "content": "Hello! How can I help you today?" },
        |      "finish_reason": "stop"
        |    }
        |  ],
        |  "usage": { "prompt_tokens": 9, "completion_tokens": 12, "total_tokens": 21 }
        |}""".stripMargin

    "decodes into OpenAI.ChatResponse" in {
      val response = decode[OpenAI.ChatResponse](json).getOrElse(fail("could not decode OpenAI example payload"))
      response.choices.head.message.role shouldBe "assistant"
      response.choices.head.finish_reason shouldBe "stop"
      response.usage.total_tokens shouldBe 21
    }
  }

  "Anthropic's documented messages response shape" - {
    val json =
      """{
        |  "id": "msg_abc123",
        |  "type": "message",
        |  "role": "assistant",
        |  "content": [ { "type": "text", "text": "Hello! How can I help you today?" } ],
        |  "model": "claude-sonnet-5",
        |  "stop_reason": "end_turn",
        |  "usage": { "input_tokens": 10, "output_tokens": 12 }
        |}""".stripMargin

    "decodes into Anthropic.MessagesResponse" in {
      val response = decode[Anthropic.MessagesResponse](json).getOrElse(fail("could not decode Anthropic example payload"))
      response.content.head.text shouldBe Some("Hello! How can I help you today?")
      response.stop_reason shouldBe "end_turn"
      response.usage.output_tokens shouldBe 12
    }
  }
}
