package com.alai.llmsim

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.parallel._
import org.http4s._
import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.deriveDecoder

import Script._

/** In-process tests: `Client.fromHttpApp` runs the routes without a real
  * socket. Every test builds its own App (its own ScriptRunner and
  * CallJournal) from its own Script, so tests never interfere with each
  * other's state.
  */
class SimulatorSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private implicit val openAIReqEnc: EntityEncoder[IO, OpenAI.ChatRequest] =
    jsonEncoderOf[IO, OpenAI.ChatRequest]
  private implicit val openAIRespDec: EntityDecoder[IO, OpenAI.ChatResponse] =
    jsonOf[IO, OpenAI.ChatResponse]
  private implicit val openAIErrDec: EntityDecoder[IO, OpenAI.ErrorBody] =
    jsonOf[IO, OpenAI.ErrorBody]

  private implicit val anthropicReqEnc: EntityEncoder[IO, Anthropic.MessagesRequest] =
    jsonEncoderOf[IO, Anthropic.MessagesRequest]
  private implicit val anthropicRespDec: EntityDecoder[IO, Anthropic.MessagesResponse] =
    jsonOf[IO, Anthropic.MessagesResponse]

  private implicit val capturedCallDecoder: Decoder[CapturedCall] = deriveDecoder
  private implicit val callRespDec: EntityDecoder[IO, CapturedCall] =
    jsonOf[IO, CapturedCall]
  private implicit val callsRespDec: EntityDecoder[IO, List[CapturedCall]] =
    jsonOf[IO, List[CapturedCall]]

  private def clientFor(script: Script): IO[Client[IO]] =
    App.build(script).map(Client.fromHttpApp)

  private def openAIRequest(text: String = "hi"): Request[IO] =
    Request[IO](Method.POST, uri"/v1/chat/completions")
      .withEntity(OpenAI.ChatRequest("gpt-4o-mini", List(OpenAI.Message("user", Some(text)))))

  private def anthropicRequest(text: String = "hi"): Request[IO] =
    Request[IO](Method.POST, uri"/v1/messages")
      .withEntity(Anthropic.MessagesRequest(
        "claude-sonnet-5", 256,
        List(Anthropic.Message("user", List(Anthropic.ContentBlock("text", Some(text)))))
      ))

  "a script's replies are consumed in order, per call" in {
    for {
      c     <- clientFor(Script.exactly(reply("first"), reply("second"), reply("third")))
      first <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second<- c.expect[OpenAI.ChatResponse](openAIRequest())
      third <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      first.choices.head.message.content shouldBe Some("first")
      second.choices.head.message.content shouldBe Some("second")
      third.choices.head.message.content shouldBe Some("third")
    }
  }

  "the same script drives both the OpenAI- and Anthropic-shaped endpoints" in {
    for {
      c    <- clientFor(Script.exactly(reply("one script"), reply("two shapes")))
      oa   <- c.expect[OpenAI.ChatResponse](openAIRequest())
      anth <- c.expect[Anthropic.MessagesResponse](anthropicRequest())
    } yield {
      oa.choices.head.message.content shouldBe Some("one script")
      anth.content.head.text shouldBe Some("two shapes")
    }
  }

  "Overrun.Fail: a call past the end of the script fails loudly" in {
    for {
      c    <- clientFor(Script.exactly(reply("only step")))
      _    <- c.expect[OpenAI.ChatResponse](openAIRequest())
      resp <- c.run(openAIRequest()).use(r => IO.pure(r.status))
    } yield resp shouldBe Status.InternalServerError
  }

  "Overrun.RepeatLast: calls past the end keep getting the final reply" in {
    for {
      c      <- clientFor(Script.repeatingLast(reply("a"), reply("b")))
      first  <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second <- c.expect[OpenAI.ChatResponse](openAIRequest())
      third  <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      first.choices.head.message.content shouldBe Some("a")
      second.choices.head.message.content shouldBe Some("b")
      third.choices.head.message.content shouldBe Some("b")
    }
  }

  "Overrun.Cycle: the script loops back to its first step" in {
    for {
      c      <- clientFor(Script.cycling(reply("a"), reply("b")))
      first  <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second <- c.expect[OpenAI.ChatResponse](openAIRequest())
      third  <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      first.choices.head.message.content shouldBe Some("a")
      second.choices.head.message.content shouldBe Some("b")
      third.choices.head.message.content shouldBe Some("a")
    }
  }

  "an Error step returns the configured status and a vendor-shaped error body" in {
    for {
      c    <- clientFor(Script.exactly(error(429, "rate limited by script")))
      resp <- c.run(openAIRequest()).use { r =>
                r.as[OpenAI.ErrorBody].map(body => (r.status, body))
              }
    } yield {
      resp._1 shouldBe Status.TooManyRequests
      resp._2.error.message shouldBe "rate limited by script"
    }
  }

  "the call journal records every call, with provider and step index" in {
    for {
      c      <- clientFor(Script.exactly(reply("first"), reply("second")))
      _      <- c.expect[OpenAI.ChatResponse](openAIRequest("hello there"))
      _      <- c.expect[Anthropic.MessagesResponse](anthropicRequest("hi again"))
      calls  <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield {
      calls.map(_.provider) shouldBe List("openai", "anthropic")
      calls.map(_.stepIndex) shouldBe List(Some(0), Some(1))
      calls(0).rawRequest.spaces2 should include("hello there")
      calls(1).rawRequest.spaces2 should include("hi again")
    }
  }

  "captured calls include normalized model and message fields" in {
    for {
      c     <- clientFor(Script.exactly(reply("ok")))
      _     <- c.expect[OpenAI.ChatResponse](openAIRequest("please help"))
      calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield {
      calls.head.model shouldBe Some("gpt-4o-mini")
      calls.head.messages shouldBe Vector(CapturedMessage("user", "please help"))
    }
  }

  "an exhausted call is journaled with no step index" in {
    for {
      c     <- clientFor(Script.exactly(reply("only step")))
      _     <- c.expect[OpenAI.ChatResponse](openAIRequest())
      _     <- c.run(openAIRequest()).use(_ => IO.unit) // second call: exhausted
      calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield calls.map(_.stepIndex) shouldBe List(Some(0), None)
  }

  "a request that fails to decode is journaled as Failed and rejected with 400" in {
    for {
      c     <- clientFor(Script.exactly(reply("unused")))
      resp  <- c.run(Request[IO](Method.POST, uri"/v1/chat/completions").withEntity(Json.obj("nonsense" -> Json.fromString("x")))).use(r => IO.pure(r.status))
      calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield {
      resp shouldBe Status.BadRequest
      calls should have size 1
      calls.head.stepIndex shouldBe None
      calls.head.outcome match {
        case CallOutcome.Failed(_) => succeed
        case other                 => fail(s"expected Failed, got $other")
      }
    }
  }

  "GET /_llmsim/calls/{sequence} returns one call, 404 if it doesn't exist" in {
    for {
      c        <- clientFor(Script.exactly(reply("a"), reply("b")))
      _        <- c.expect[OpenAI.ChatResponse](openAIRequest())
      _        <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second   <- c.expect[CapturedCall](Request[IO](Method.GET, uri"/_llmsim/calls/2"))
      missing  <- c.run(Request[IO](Method.GET, uri"/_llmsim/calls/99")).use(r => IO.pure(r.status))
    } yield {
      second.sequence shouldBe 2L
      missing shouldBe Status.NotFound
    }
  }

  "DELETE /_llmsim/calls clears the journal but does NOT rewind the script" in {
    for {
      c      <- clientFor(Script.exactly(reply("a"), reply("b")))
      _      <- c.expect[OpenAI.ChatResponse](openAIRequest())
      _      <- c.expect[String](Request[IO](Method.DELETE, uri"/_llmsim/calls"))
      calls  <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      second <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      calls shouldBe empty
      second.choices.head.message.content shouldBe Some("b") // continued, not rewound to "a"
    }
  }

  "POST /_llmsim/reset clears the journal and rewinds the script" in {
    for {
      c       <- clientFor(Script.exactly(reply("a"), reply("b")))
      _       <- c.expect[OpenAI.ChatResponse](openAIRequest())
      _       <- c.expect[String](Request[IO](Method.POST, uri"/_llmsim/reset"))
      calls   <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      afterReset <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      calls shouldBe empty
      afterReset.choices.head.message.content shouldBe Some("a") // back to the first step
    }
  }

  "the journal is bounded: oldest entries are dropped once the cap is exceeded" in {
    for {
      journal <- CallJournal.inMemory(maxEntries = 2)
      _       <- journal.record("openai", None, Vector.empty, Json.obj(), CallOutcome.Responded(200, Json.obj()), Some(0))
      _       <- journal.record("openai", None, Vector.empty, Json.obj(), CallOutcome.Responded(200, Json.obj()), Some(1))
      _       <- journal.record("openai", None, Vector.empty, Json.obj(), CallOutcome.Responded(200, Json.obj()), Some(2))
      calls   <- journal.all
    } yield calls.map(_.sequence) shouldBe List(2L, 3L)
  }

  "concurrent recordings never lose or reorder a sequence number" in {
    val n = 200
    for {
      journal <- CallJournal.inMemory(maxEntries = n)
      _       <- (1 to n).toList.parTraverse { i =>
                   journal.record("openai", None, Vector.empty, Json.obj(), CallOutcome.Responded(200, Json.obj()), Some(i))
                 }
      calls   <- journal.all
    } yield {
      // every sequence number from 1..n present exactly once, and the
      // array itself is in that same order -- both would fail under the
      // old two-Ref design if two requests interleaved their two separate
      // atomic steps (claim a sequence, then append).
      calls.map(_.sequence) shouldBe (1L to n.toLong).toList
    }
  }

  "a ToolCall step against OpenAI: model requests a tool, then the follow-up call gets the next step" in {
    for {
      c <- clientFor(Script.exactly(
             toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}"""),
             reply("It's sunny in SF.")
           ))
      first <- c.expect[OpenAI.ChatResponse](openAIRequest())
      // the app's follow-up call carries the tool result as a "tool" role message
      second <- c.expect[OpenAI.ChatResponse](Request[IO](Method.POST, uri"/v1/chat/completions").withEntity(
                  OpenAI.ChatRequest("gpt-4o-mini", List(
                    OpenAI.Message("user", Some("what's the weather in SF?")),
                    OpenAI.Message("assistant", None, tool_calls = Some(List(
                      OpenAI.ToolCall("call-1", "function", OpenAI.FunctionCall("get_weather", """{"city":"SF"}"""))
                    ))),
                    OpenAI.Message("tool", Some("72F and sunny"), tool_call_id = Some("call-1"))
                  ))
                ))
    } yield {
      first.choices.head.message.content shouldBe None
      first.choices.head.message.tool_calls.get.head.function.name shouldBe "get_weather"
      first.choices.head.finish_reason shouldBe "tool_calls"
      second.choices.head.message.content shouldBe Some("It's sunny in SF.")
    }
  }

  "a ToolCall step against Anthropic: input is a real JSON object, stop_reason is tool_use" in {
    for {
      c    <- clientFor(Script.exactly(toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}""")))
      resp <- c.expect[Anthropic.MessagesResponse](anthropicRequest())
    } yield {
      resp.stop_reason shouldBe "tool_use"
      resp.content.head.`type` shouldBe "tool_use"
      resp.content.head.name shouldBe Some("get_weather")
      resp.content.head.input.flatMap(_.asObject).flatMap(_("city")).flatMap(_.asString) shouldBe Some("SF")
    }
  }

  "a ToolCall step with malformed arguments works fine against OpenAI but fails loudly against Anthropic" in {
    val badArgs = """{"city": """
    for {
      c1         <- clientFor(Script.exactly(toolCall(id = "call-1", name = "get_weather", arguments = badArgs)))
      openAIResp <- c1.expect[OpenAI.ChatResponse](openAIRequest())

      c2         <- clientFor(Script.exactly(toolCall(id = "call-1", name = "get_weather", arguments = badArgs)))
      anthStatus <- c2.run(anthropicRequest()).use(r => IO.pure(r.status))
      calls      <- c2.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield {
      // OpenAI's arguments field is just a string -- llmsim never validates
      // it, so a malformed one passes straight through unchanged.
      openAIResp.choices.head.message.tool_calls.get.head.function.arguments shouldBe badArgs

      // Anthropic's input is a real nested JSON object at the wire level,
      // so this can't be represented there -- the simulator fails loudly
      // instead of silently coercing something misleading.
      anthStatus shouldBe Status.InternalServerError
      calls.head.outcome match {
        case CallOutcome.Failed(msg) => msg should include("aren't valid JSON")
        case other                   => fail(s"expected Failed, got $other")
      }
    }
  }

  "captured messages normalize a tool call to a readable summary" in {
    for {
      c     <- clientFor(Script.exactly(reply("unused")))
      _     <- c.expect[OpenAI.ChatResponse](Request[IO](Method.POST, uri"/v1/chat/completions").withEntity(
                 OpenAI.ChatRequest("gpt-4o-mini", List(
                   OpenAI.Message("assistant", None, tool_calls = Some(List(
                     OpenAI.ToolCall("call-1", "function", OpenAI.FunctionCall("get_weather", """{"city":"SF"}"""))
                   )))
                 ))
               ))
      calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield calls.head.messages.head.content should include("get_weather")
  }

  "a ReplyFromToolResult step against OpenAI: reply is built from the real tool result" in {
    for {
      c <- clientFor(Script.exactly(
             toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}"""),
             replyFromToolResult("call-1")(result => s"The tool said: $result")
           ))
      _      <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second <- c.expect[OpenAI.ChatResponse](Request[IO](Method.POST, uri"/v1/chat/completions").withEntity(
                  OpenAI.ChatRequest("gpt-4o-mini", List(
                    OpenAI.Message("user", Some("what's the weather in SF?")),
                    OpenAI.Message("assistant", None, tool_calls = Some(List(
                      OpenAI.ToolCall("call-1", "function", OpenAI.FunctionCall("get_weather", """{"city":"SF"}"""))
                    ))),
                    OpenAI.Message("tool", Some("72F and sunny"), tool_call_id = Some("call-1"))
                  ))
                ))
    } yield second.choices.head.message.content shouldBe Some("The tool said: 72F and sunny")
  }

  "a ReplyFromToolResult step against Anthropic: reply is built from the real tool_result content block" in {
    for {
      c <- clientFor(Script.exactly(
             toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}"""),
             replyFromToolResult("call-1")(result => s"The tool said: $result")
           ))
      _      <- c.expect[Anthropic.MessagesResponse](anthropicRequest())
      second <- c.expect[Anthropic.MessagesResponse](Request[IO](Method.POST, uri"/v1/messages").withEntity(
                  Anthropic.MessagesRequest("claude-sonnet-5", 256, List(
                    Anthropic.Message("user", List(Anthropic.ContentBlock("text", Some("what's the weather in SF?")))),
                    Anthropic.Message("assistant", List(Anthropic.ContentBlock(
                      "tool_use", id = Some("call-1"), name = Some("get_weather"),
                      input = Some(Json.obj("city" -> Json.fromString("SF")))
                    ))),
                    Anthropic.Message("user", List(Anthropic.ContentBlock(
                      "tool_result", tool_use_id = Some("call-1"), content = Some(Json.fromString("72F and sunny"))
                    )))
                  ))
                ))
    } yield second.content.head.text shouldBe Some("The tool said: 72F and sunny")
  }

  "a ReplyFromToolResult step fails loudly when no matching tool_result is found" in {
    for {
      c     <- clientFor(Script.exactly(replyFromToolResult("call-1")(result => s"got: $result")))
      resp  <- c.run(openAIRequest()).use(r => IO.pure(r.status))
      calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
    } yield {
      resp shouldBe Status.InternalServerError
      calls.head.outcome match {
        case CallOutcome.Failed(msg) => msg should include("tool_call_id")
        case other                   => fail(s"expected Failed, got $other")
      }
    }
  }
}
