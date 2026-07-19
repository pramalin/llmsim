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
      .withEntity(OpenAI.ChatRequest("gpt-4o-mini", List(OpenAI.Message("user", text))))

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
      first.choices.head.message.content shouldBe "first"
      second.choices.head.message.content shouldBe "second"
      third.choices.head.message.content shouldBe "third"
    }
  }

  "the same script drives both the OpenAI- and Anthropic-shaped endpoints" in {
    for {
      c    <- clientFor(Script.exactly(reply("one script"), reply("two shapes")))
      oa   <- c.expect[OpenAI.ChatResponse](openAIRequest())
      anth <- c.expect[Anthropic.MessagesResponse](anthropicRequest())
    } yield {
      oa.choices.head.message.content shouldBe "one script"
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
      first.choices.head.message.content shouldBe "a"
      second.choices.head.message.content shouldBe "b"
      third.choices.head.message.content shouldBe "b"
    }
  }

  "Overrun.Cycle: the script loops back to its first step" in {
    for {
      c      <- clientFor(Script.cycling(reply("a"), reply("b")))
      first  <- c.expect[OpenAI.ChatResponse](openAIRequest())
      second <- c.expect[OpenAI.ChatResponse](openAIRequest())
      third  <- c.expect[OpenAI.ChatResponse](openAIRequest())
    } yield {
      first.choices.head.message.content shouldBe "a"
      second.choices.head.message.content shouldBe "b"
      third.choices.head.message.content shouldBe "a"
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
      second.choices.head.message.content shouldBe "b" // continued, not rewound to "a"
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
      afterReset.choices.head.message.content shouldBe "a" // back to the first step
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
}
