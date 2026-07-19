package com.alai.llmsim

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s._
import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import Script._

/** In-process tests: `Client.fromHttpApp` runs the routes without a real
  * socket. Every test builds its own ScriptRunner from its own Script, so
  * tests never interfere with each other's step position -- there's no
  * shared mutable scenario table left to worry about.
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

  private def clientFor(script: Script): IO[Client[IO]] =
    ScriptRunner.from(script).map(runner => Client.fromHttpApp(Simulator.routes(runner).orNotFound))

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
}
