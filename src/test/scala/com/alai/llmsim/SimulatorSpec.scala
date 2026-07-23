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
import io.circe.parser.{parse => parseJson}

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

  private def openAIStreamingRequest(text: String = "hi"): Request[IO] =
    Request[IO](Method.POST, uri"/v1/chat/completions")
      .withEntity(OpenAI.ChatRequest("gpt-4o-mini", List(OpenAI.Message("user", Some(text))), stream = Some(true)))

  private def anthropicRequest(text: String = "hi"): Request[IO] =
    Request[IO](Method.POST, uri"/v1/messages")
      .withEntity(Anthropic.MessagesRequest(
        "claude-sonnet-5", 256,
        List(Anthropic.Message("user", List(Anthropic.ContentBlock("text", Some(text)))))
      ))

  private def anthropicStreamingRequest(text: String = "hi"): Request[IO] =
    Request[IO](Method.POST, uri"/v1/messages")
      .withEntity(Anthropic.MessagesRequest(
        "claude-sonnet-5", 256,
        List(Anthropic.Message("user", List(Anthropic.ContentBlock("text", Some(text))))),
        stream = Some(true)
      ))

  // Splits a raw SSE body on the blank-line frame separator and parses
  // each frame's optional "event:" line and required "data:" line. Used
  // to assert on the actual wire framing, not just typed response
  // decoding -- that framing is exactly what a real client parses.
  private def parseFrames(body: String): List[(Option[String], String)] =
    body.split("\n\n").filter(_.nonEmpty).toList.map { frame =>
      val lines     = frame.linesIterator.toList
      val eventLine = lines.collectFirst { case l if l.startsWith("event: ") => l.stripPrefix("event: ") }
      val dataLine  = lines.collectFirst { case l if l.startsWith("data: ") => l.stripPrefix("data: ") }.getOrElse("")
      (eventLine, dataLine)
    }

  // Makes whitespace-containing test names actually readable in output
  // -- a raw literal space/newline/tab in a ScalaTest test name is easy
  // to misread or invisible entirely.
  private def displayable(s: String): String =
    s.flatMap {
      case '\n' => "\\n"
      case '\t' => "\\t"
      case ' '  => "\u00b7"
      case c    => c.toString
    }

  // Wraps journal.begin + journal.complete for tests that record
  // directly against a CallJournal (not through a live route) --
  // begin/complete replaced the old single-shot record, see
  // CallJournal.scala.
  private def recordCall(journal: CallJournal, provider: String, stepIndex: Option[Int]): IO[CapturedCall] =
    for {
      handle <- journal.begin(provider, None, Vector.empty, Json.obj(), System.currentTimeMillis())
      call   <- journal.complete(handle, CallOutcome.Responded(200, Json.obj()), stepIndex, System.currentTimeMillis(), 0L)
    } yield call

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
      _       <- recordCall(journal, "openai", Some(0))
      _       <- recordCall(journal, "openai", Some(1))
      _       <- recordCall(journal, "openai", Some(2))
      calls   <- journal.all
    } yield calls.map(_.sequence) shouldBe List(2L, 3L)
  }

  "concurrent recordings never lose or reorder a sequence number" in {
    val n = 200
    for {
      journal <- CallJournal.inMemory(maxEntries = n)
      _       <- (1 to n).toList.parTraverse { i =>
                   recordCall(journal, "openai", Some(i))
                 }
      calls   <- journal.all
    } yield {
      // Every sequence number from 1..n present exactly once, in that
      // exact order. begin() and complete() are two separate steps now
      // (not one atomic record() call), so 200 concurrent recordCall
      // invocations can genuinely finish complete() in a different
      // order than they started -- what guarantees this test passes
      // anyway is CallJournal.complete's sortBy(_.sequence) on every
      // write, not the absence of interleaving.
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

  // ---------------------------------------------------------------------
  // Scriptable response headers (roadmap item 2b). These are the
  // authoritative tests for this feature -- they assert on the raw wire
  // response, independent of any client library's interpretation of it.
  // Whether a given Spring AI version correctly consumes these headers
  // into ChatResponseMetadata#getRateLimit() is a separate question,
  // answered by ci/spring-verification's own tests, not here. Raw
  // strings throughout: llmsim relays exactly what the script wrote, it
  // doesn't interpret or reformat a rate-limit concept -- see Step's
  // `headers` field for why.
  // ---------------------------------------------------------------------

  "OpenAI-shaped headers" - {

    "a Reply step's headers are returned verbatim on the 200 response" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi", headers = Map(
                  "x-ratelimit-limit-requests"     -> "60",
                  "x-ratelimit-remaining-requests"  -> "59",
                  "x-ratelimit-reset-requests"      -> "1s",
                  "x-ratelimit-limit-tokens"        -> "150000",
                  "x-ratelimit-remaining-tokens"    -> "149984",
                  "x-ratelimit-reset-tokens"        -> "6m0s"
                ))))
        resp <- c.run(openAIRequest()).use(r => IO.pure(r.headers))
      } yield {
        resp.get(org.typelevel.ci.CIString("x-ratelimit-remaining-requests")).map(_.head.value) shouldBe Some("59")
        resp.get(org.typelevel.ci.CIString("x-ratelimit-reset-tokens")).map(_.head.value) shouldBe Some("6m0s")
      }
    }

    "a Reply step's headers are also captured in the journal, not just sent on the wire" in {
      for {
        c     <- clientFor(Script.exactly(reply("hi", headers = Map("x-ratelimit-remaining-requests" -> "59"))))
        _     <- c.expect[String](openAIRequest())
        calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      } yield {
        calls.head.responseHeaders should contain(CapturedHeader("x-ratelimit-remaining-requests", "59"))
      }
    }


    "an Error step's headers (retry-after on a 429) are returned verbatim" in {
      for {
        c    <- clientFor(Script.exactly(error(429, "rate limit exceeded", headers = Map(
                  "retry-after"                    -> "5",
                  "x-ratelimit-remaining-requests"  -> "0",
                  "x-ratelimit-reset-requests"      -> "5s"
                ))))
        resp <- c.run(openAIRequest()).use(r => IO.pure((r.status, r.headers)))
      } yield {
        resp._1 shouldBe Status.TooManyRequests
        resp._2.get(org.typelevel.ci.CIString("retry-after")).map(_.head.value) shouldBe Some("5")
      }
    }

    "a declining-then-throttled sequence is fully scriptable across calls" in {
      for {
        c      <- clientFor(Script.exactly(
                    reply("first", headers = Map("x-ratelimit-remaining-requests" -> "2")),
                    reply("second", headers = Map("x-ratelimit-remaining-requests" -> "1")),
                    error(429, "rate limit exceeded", headers = Map("retry-after" -> "5"))
                  ))
        first  <- c.run(openAIRequest()).use(r => IO.pure(r.headers.get(org.typelevel.ci.CIString("x-ratelimit-remaining-requests")).map(_.head.value)))
        second <- c.run(openAIRequest()).use(r => IO.pure(r.headers.get(org.typelevel.ci.CIString("x-ratelimit-remaining-requests")).map(_.head.value)))
        third  <- c.run(openAIRequest()).use(r => IO.pure((r.status, r.headers.get(org.typelevel.ci.CIString("retry-after")).map(_.head.value))))
      } yield {
        first shouldBe Some("2")
        second shouldBe Some("1")
        third shouldBe ((Status.TooManyRequests, Some("5")))
      }
    }

    "no headers are added when a script doesn't specify any" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi")))
        resp <- c.run(openAIRequest()).use(r => IO.pure(r.headers))
      } yield resp.get(org.typelevel.ci.CIString("x-ratelimit-remaining-requests")) shouldBe None
    }
  }

  "Anthropic-shaped headers" - {

    "a Reply step's headers use Anthropic's own header names and RFC 3339 reset format" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi", headers = Map(
                  "anthropic-ratelimit-requests-limit"      -> "1000",
                  "anthropic-ratelimit-requests-remaining"  -> "999",
                  "anthropic-ratelimit-requests-reset"      -> "2026-07-21T19:00:00Z",
                  "anthropic-ratelimit-input-tokens-limit"  -> "40000",
                  "anthropic-ratelimit-output-tokens-limit" -> "8000"
                ))))
        resp <- c.run(anthropicRequest()).use(r => IO.pure(r.headers))
      } yield {
        resp.get(org.typelevel.ci.CIString("anthropic-ratelimit-requests-remaining")).map(_.head.value) shouldBe Some("999")
        resp.get(org.typelevel.ci.CIString("anthropic-ratelimit-requests-reset")).map(_.head.value) shouldBe Some("2026-07-21T19:00:00Z")
      }
    }
  }

  "array-shaped message content" - {

    "OpenAI array-of-parts content is joined with spaces, not concatenated" in {
      // OpenAI.Message.content is Option[String] on the typed encoder,
      // so openAIRequest() can never exercise the array-of-parts decode
      // path -- a raw JSON body is the only way to test it directly.
      val rawBody = Json.obj(
        "model" -> Json.fromString("gpt-4o-mini"),
        "messages" -> Json.arr(
          Json.obj(
            "role" -> Json.fromString("user"),
            "content" -> Json.arr(
              Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString("hello")),
              Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString("world"))
            )
          )
        )
      )
      for {
        c     <- clientFor(Script.exactly(reply("hi")))
        _     <- c.expect[String](Request[IO](Method.POST, uri"/v1/chat/completions").withEntity(rawBody))
        calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      } yield {
        // Not "helloworld" -- see Protocol.scala's arrayContentAsString.
        calls.head.messages.head.content shouldBe "hello world"
      }
    }
  }

  // ---------------------------------------------------------------------
  // SSE streaming (roadmap item 12). MVP: whole units per chunk (a whole
  // word, a whole tool call) -- see Simulator.scala's wordChunks and the
  // MVP comments on the streaming Protocol shapes for why finer-grained
  // splitting is deliberately deferred to fault injection (item 14). A
  // real Spring AI client's ability to actually parse this is checked
  // separately, in ci/spring-verification -- these are the authoritative
  // tests for what llmsim actually puts on the wire.
  // ---------------------------------------------------------------------

  "OpenAI SSE streaming" - {

    "a Reply step streams as data-only chunks, reconstructing the full text, terminated by [DONE]" in {
      for {
        c        <- clientFor(Script.exactly(reply("hello there world")))
        response <- c.run(openAIStreamingRequest()).use { resp =>
                      resp.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value) shouldBe
                        Some("text/event-stream; charset=utf-8")
                      resp.bodyText.compile.string
                    }
        frames = parseFrames(response)
      } yield {
        frames.last._2 shouldBe "[DONE]"
        val reconstructed = frames.init.flatMap { case (_, data) =>
          parseJson(data).toOption.flatMap(_.hcursor.downField("choices").downArray
            .downField("delta").downField("content").as[String].toOption)
        }.mkString
        reconstructed shouldBe "hello there world"
      }
    }

    "a ToolCall step streams the tool call in one chunk, ending with finish_reason tool_calls" in {
      for {
        c        <- clientFor(Script.exactly(toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}""")))
        response <- c.expect[String](openAIStreamingRequest("what's the weather?"))
        frames = parseFrames(response)
      } yield {
        frames.last._2 shouldBe "[DONE]"
        val toolCallChunk = frames.init.map(_._2).flatMap(parseJson(_).toOption).find { j =>
          // .downField alone isn't enough: circe's derived encoder writes
          // an absent Option as explicit `"tool_calls": null`, not an
          // omitted key, so the field "exists" on every chunk. .downArray
          // actually tries to enter it, which only succeeds on the one
          // chunk where it's a real (non-null) array.
          j.hcursor.downField("choices").downArray.downField("delta").downField("tool_calls").downArray.succeeded
        }.get
        val fn = toolCallChunk.hcursor.downField("choices").downArray.downField("delta")
          .downField("tool_calls").downArray.downField("function")
        fn.downField("name").as[String] shouldBe Right("get_weather")
        fn.downField("arguments").as[String] shouldBe Right("""{"city":"SF"}""")

        val lastChunk = frames.init.last._2
        parseJson(lastChunk).toOption.get.hcursor
          .downField("choices").downArray.downField("finish_reason").as[String] shouldBe Right("tool_calls")
      }
    }

    "the journal records a streamed call with streamed=true and the same aggregate body shape a non-streaming call would get" in {
      for {
        c     <- clientFor(Script.exactly(reply("hi")))
        _     <- c.expect[String](openAIStreamingRequest())
        calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      } yield {
        calls.head.streamed shouldBe true
        calls.head.outcome match {
          case CallOutcome.Responded(200, body) =>
            body.hcursor.downField("choices").downArray.downField("message").downField("content").as[String] shouldBe Right("hi")
          case other => fail(s"expected Responded, got $other")
        }
      }
    }

    "a script's headers still apply to a streamed response" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi", headers = Map("x-ratelimit-remaining-requests" -> "59"))))
        resp <- c.run(openAIStreamingRequest()).use(r => IO.pure(r.headers))
      } yield resp.get(org.typelevel.ci.CIString("x-ratelimit-remaining-requests")).map(_.head.value) shouldBe Some("59")
    }

    "the same script step still answers non-streaming when stream isn't set" in {
      for {
        c        <- clientFor(Script.exactly(reply("hello there world")))
        response <- c.expect[OpenAI.ChatResponse](openAIRequest())
      } yield response.choices.head.message.content shouldBe Some("hello there world")
    }

    // Regression cases for the trailing-whitespace bug wordChunks had:
    // String.split(" ") (no explicit limit) silently drops TRAILING
    // empty strings, so "hello " streamed as "hello" (space lost) and
    // "   " streamed as "" (nothing at all) -- streaming and
    // non-streaming returning different content for the identical
    // scripted string, contradicting the whole "same script, different
    // transport" design this feature exists to preserve. Fixed via
    // split(" ", -1); these are the cases that would have caught it.
    List("hello world", "", " ", "hello ", "  hello", "hello  world", "hello   ",
         "hello\nworld", "hello\tworld", "Hello \ud83d\udc4b world").foreach { text =>
      s"a Reply step with text '${displayable(text)}' streams to exactly the scripted string, whitespace included" in {
        for {
          c        <- clientFor(Script.exactly(reply(text)))
          response <- c.run(openAIStreamingRequest()).use(_.bodyText.compile.string)
          reconstructed = parseFrames(response).init.flatMap { case (_, data) =>
            parseJson(data).toOption.flatMap(_.hcursor.downField("choices").downArray
              .downField("delta").downField("content").as[String].toOption)
          }.mkString
        } yield reconstructed shouldBe text
      }
    }
  }

  "Anthropic SSE streaming" - {

    "a Reply step emits message_start .. message_stop, reconstructing the reply from content_block_delta text" in {
      for {
        c        <- clientFor(Script.exactly(reply("hello there world")))
        response <- c.expect[String](anthropicStreamingRequest())
        frames = parseFrames(response)
      } yield {
        frames.map(_._1) shouldBe List(
          Some("message_start"), Some("content_block_start"),
          Some("content_block_delta"), Some("content_block_delta"), Some("content_block_delta"),
          Some("content_block_stop"), Some("message_delta"), Some("message_stop")
        )
        val reconstructed = frames.collect { case (Some("content_block_delta"), data) =>
          parseJson(data).toOption.flatMap(_.hcursor.downField("delta").downField("text").as[String].toOption)
        }.flatten.mkString
        reconstructed shouldBe "hello there world"
      }
    }

    "a ToolCall step streams tool_use via a single input_json_delta carrying the full arguments" in {
      for {
        c        <- clientFor(Script.exactly(toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"SF"}""")))
        response <- c.expect[String](anthropicStreamingRequest("what's the weather?"))
        frames = parseFrames(response)
      } yield {
        val delta = frames.collectFirst { case (Some("content_block_delta"), data) => data }.get
        val json  = parseJson(delta).toOption.get
        json.hcursor.downField("delta").downField("type").as[String] shouldBe Right("input_json_delta")
        json.hcursor.downField("delta").downField("partial_json").as[String] shouldBe Right("""{"city":"SF"}""")

        val messageDelta = frames.collectFirst { case (Some("message_delta"), data) => data }.get
        parseJson(messageDelta).toOption.get.hcursor
          .downField("delta").downField("stop_reason").as[String] shouldBe Right("tool_use")
      }
    }

    "the journal records a streamed call with streamed=true" in {
      for {
        c     <- clientFor(Script.exactly(reply("hi")))
        _     <- c.expect[String](anthropicStreamingRequest())
        calls <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      } yield calls.head.streamed shouldBe true
    }

    "the same script step still answers non-streaming when stream isn't set" in {
      for {
        c        <- clientFor(Script.exactly(reply("hello there world")))
        response <- c.expect[Anthropic.MessagesResponse](anthropicRequest())
      } yield response.content.head.text shouldBe Some("hello there world")
    }

    // Real Anthropic events omit an absent optional field entirely
    // rather than sending it as JSON null -- a text content_block_start
    // is documented as exactly {"type":"text","text":""}, not six keys
    // with five of them null. circe's derived encoders include every
    // Option field as explicit null by default; these confirm the
    // dropNullValues fix in sseFrame actually took effect on the wire,
    // not just that behavioral reconstruction still happens to work.
    "a text content_block_start has no extraneous null keys" in {
      for {
        c        <- clientFor(Script.exactly(reply("hi")))
        response <- c.expect[String](anthropicStreamingRequest())
        frames = parseFrames(response)
      } yield {
        val data = frames.collectFirst { case (Some("content_block_start"), d) => d }.get
        val obj  = parseJson(data).toOption.get.hcursor.downField("content_block").focus.get.asObject.get
        obj.keys.toSet shouldBe Set("type", "text")
      }
    }

    "message_delta.usage carries only output_tokens, not a null input_tokens" in {
      for {
        c        <- clientFor(Script.exactly(reply("hi")))
        response <- c.expect[String](anthropicStreamingRequest())
        frames = parseFrames(response)
      } yield {
        val data = frames.collectFirst { case (Some("message_delta"), d) => d }.get
        val obj  = parseJson(data).toOption.get.hcursor.downField("usage").focus.get.asObject.get
        obj.keys.toSet shouldBe Set("output_tokens")
      }
    }

    // Same regression coverage as the OpenAI block above, for the
    // Anthropic-shaped endpoint -- wordChunks is shared between both.
    List("hello world", "", " ", "hello ", "  hello", "hello  world", "hello   ",
         "hello\nworld", "hello\tworld", "Hello \ud83d\udc4b world").foreach { text =>
      s"a Reply step with text '${displayable(text)}' streams to exactly the scripted string, whitespace included" in {
        for {
          c        <- clientFor(Script.exactly(reply(text)))
          response <- c.expect[String](anthropicStreamingRequest())
          reconstructed = parseFrames(response).collect { case (Some("content_block_delta"), data) =>
            parseJson(data).toOption.flatMap(_.hcursor.downField("delta").downField("text").as[String].toOption)
          }.flatten.mkString
        } yield reconstructed shouldBe text
      }
    }
  }

  "GET /v1/models" - {

    "with no anthropic-version header, returns the OpenAI-shaped list" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi")))
        body <- c.expect[Json](Request[IO](Method.GET, uri"/v1/models"))
      } yield {
        body.hcursor.downField("object").as[String] shouldBe Right("list")
        body.hcursor.downField("data").downArray.downField("id").as[String] shouldBe Right("gpt-4o-mini")
        body.hcursor.downField("data").downArray.downField("owned_by").as[String] shouldBe Right("openai")
      }
    }

    "with an anthropic-version header, returns the Anthropic-shaped list" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi")))
        body <- c.expect[Json](Request[IO](Method.GET, uri"/v1/models")
                  .putHeaders(Header.Raw(org.typelevel.ci.CIString("anthropic-version"), "2023-06-01")))
      } yield {
        body.hcursor.downField("data").downArray.downField("id").as[String] shouldBe Right("claude-sonnet-5")
        body.hcursor.downField("data").downArray.downField("display_name").as[String] shouldBe Right("Claude Sonnet 5")
        body.hcursor.downField("has_more").as[Boolean] shouldBe Right(false)
      }
    }

    "doesn't touch the script position or the journal" in {
      for {
        c      <- clientFor(Script.exactly(reply("a"), reply("b")))
        _      <- c.expect[Json](Request[IO](Method.GET, uri"/v1/models"))
        _      <- c.expect[Json](Request[IO](Method.GET, uri"/v1/models"))
        result <- c.expect[OpenAI.ChatResponse](openAIRequest())
        calls  <- c.expect[List[CapturedCall]](Request[IO](Method.GET, uri"/_llmsim/calls"))
      } yield {
        // Two /v1/models calls didn't consume a step -- this still gets
        // script step 0 ("a"), not step 2 (which wouldn't even exist).
        result.choices.head.message.content shouldBe Some("a")
        calls.size shouldBe 1
      }
    }
  }
}
