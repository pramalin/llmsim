package com.alai.llmsim

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s._
import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import io.circe.Json

import Script._
import Dashboard._

/** Covers three things: `Dashboard.summarize` (a pure function, tested
  * directly against hand-built `CapturedCall`/`ScriptStatus` values, no
  * IO or HTTP needed), `ScriptRunner.status` (needs IO, but no HTTP),
  * and the two new routes end to end.
  */
class DashboardSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  // A minimal, otherwise-irrelevant-field CapturedCall -- Dashboard.
  // summarize only reads provider/outcome/durationMillis/streamed/
  // receivedAtEpochMillis, everything else here is a placeholder.
  private def call(
      provider: String,
      outcome: CallOutcome,
      durationMillis: Long,
      streamed: Boolean = false,
      receivedAt: Long = 0L
  ): CapturedCall =
    CapturedCall(
      sequence = 0L, provider = provider, model = None, messages = Vector.empty,
      rawRequest = Json.obj(), outcome = outcome, stepIndex = Some(0),
      receivedAtEpochMillis = receivedAt, completedAtEpochMillis = receivedAt,
      durationMillis = durationMillis, streamed = streamed
    )

  private val runningStatus =
    ScriptStatus(totalSteps = 3, nextStepIndex = Some(1), onOverrun = Overrun.Fail, exhausted = false)

  "Dashboard.summarize" - {

    "an empty journal reports zero counts and no latency/timestamp data" in {
      val summary = Dashboard.summarize(Nil, journalCapacity = 1000, runningStatus, scriptName = None)

      summary.journal shouldBe JournalSummary(retainedCalls = 0, capacity = 1000)
      summary.calls.byOutcome shouldBe Map("responded" -> 0, "rejected" -> 0, "failed" -> 0, "cancelled" -> 0)
      summary.calls.byProvider shouldBe Map("openai" -> 0, "anthropic" -> 0)
      summary.calls.streamed shouldBe 0
      summary.latencyMillis shouldBe LatencySummary(sampleCount = 0, average = None, p95 = None, max = None)
      summary.lastCallAtEpochMillis shouldBe None
    }

    "mixed providers, outcomes, and streaming modes are counted correctly" in {
      val calls = List(
        call("openai", CallOutcome.Responded(200, Json.obj()), 10, streamed = true, receivedAt = 100),
        call("openai", CallOutcome.Rejected(429, "rate limited"), 5, receivedAt = 200),
        call("anthropic", CallOutcome.Responded(200, Json.obj()), 20, receivedAt = 300),
        call("anthropic", CallOutcome.Failed("decode error"), 1, receivedAt = 400),
        call("openai", CallOutcome.Cancelled("client disconnected before the stream completed"), 15, streamed = true, receivedAt = 500)
      )
      val summary = Dashboard.summarize(calls, journalCapacity = 1000, runningStatus, scriptName = None)

      summary.calls.byOutcome shouldBe Map("responded" -> 2, "rejected" -> 1, "failed" -> 1, "cancelled" -> 1)
      summary.calls.byProvider shouldBe Map("openai" -> 3, "anthropic" -> 2)
      summary.calls.streamed shouldBe 2
      // Calls are in journal (received) order -- the last element in the
      // input list is the most recently received call.
      summary.lastCallAtEpochMillis shouldBe Some(500L)
    }

    "a single call: average, p95, and max all equal its duration" in {
      val calls = List(call("openai", CallOutcome.Responded(200, Json.obj()), 42))
      val summary = Dashboard.summarize(calls, journalCapacity = 1000, runningStatus, scriptName = None)

      summary.latencyMillis shouldBe LatencySummary(sampleCount = 1, average = Some(42.0), p95 = Some(42L), max = Some(42L))
    }

    "a known percentile sample confirms the nearest-rank rule" in {
      // 20 samples: 1..20. Nearest-rank p95 = ceil(20 * 0.95) = 19th
      // smallest (1-indexed) = 19.
      val calls = (1 to 20).map(d => call("openai", CallOutcome.Responded(200, Json.obj()), d.toLong)).toList
      val summary = Dashboard.summarize(calls, journalCapacity = 1000, runningStatus, scriptName = None)

      summary.latencyMillis.sampleCount shouldBe 20
      summary.latencyMillis.average shouldBe Some(10.5)
      summary.latencyMillis.p95 shouldBe Some(19L)
      summary.latencyMillis.max shouldBe Some(20L)
    }

    "a small journal capacity is reported as-is, independent of how many calls are passed in" in {
      // summarize itself doesn't enforce capacity -- that's CallJournal's
      // job (see the route-level test below for the real eviction
      // behavior); this just confirms the reported capacity value is
      // whatever's passed in, not hardcoded.
      val summary = Dashboard.summarize(Nil, journalCapacity = 5, runningStatus, scriptName = None)
      summary.journal.capacity shouldBe 5
    }

    "script status: still running" in {
      val status = ScriptStatus(totalSteps = 3, nextStepIndex = Some(1), onOverrun = Overrun.Fail, exhausted = false)
      val summary = Dashboard.summarize(Nil, 1000, status, scriptName = Some("com.example.Flow"))

      summary.script shouldBe ScriptSummary(
        name = Some("com.example.Flow"), totalSteps = 3, nextStepIndex = Some(1),
        onOverrun = "fail", exhausted = false
      )
    }

    "script status: exhausted under Overrun.Fail has no next step" in {
      val status = ScriptStatus(totalSteps = 3, nextStepIndex = None, onOverrun = Overrun.Fail, exhausted = true)
      val summary = Dashboard.summarize(Nil, 1000, status, scriptName = None)

      summary.script.nextStepIndex shouldBe None
      summary.script.exhausted shouldBe true
      summary.script.onOverrun shouldBe "fail"
    }

    "script status: RepeatLast reports the final step as next, not exhausted" in {
      val status = ScriptStatus(totalSteps = 3, nextStepIndex = Some(2), onOverrun = Overrun.RepeatLast, exhausted = false)
      val summary = Dashboard.summarize(Nil, 1000, status, scriptName = None)

      summary.script.nextStepIndex shouldBe Some(2)
      summary.script.exhausted shouldBe false
      summary.script.onOverrun shouldBe "repeatLast"
    }

    "script status: Cycle reports step 0 as next, not exhausted" in {
      val status = ScriptStatus(totalSteps = 3, nextStepIndex = Some(0), onOverrun = Overrun.Cycle, exhausted = false)
      val summary = Dashboard.summarize(Nil, 1000, status, scriptName = None)

      summary.script.nextStepIndex shouldBe Some(0)
      summary.script.exhausted shouldBe false
      summary.script.onOverrun shouldBe "cycle"
    }
  }

  "ScriptRunner.status" - {

    "reports the next step index while the script is still running" in {
      for {
        runner <- ScriptRunner.from(Script.exactly(reply("a"), reply("b"), reply("c")))
        before <- runner.status
        _      <- runner.next
        after  <- runner.status
      } yield {
        before shouldBe ScriptStatus(totalSteps = 3, nextStepIndex = Some(0), onOverrun = Overrun.Fail, exhausted = false)
        after  shouldBe ScriptStatus(totalSteps = 3, nextStepIndex = Some(1), onOverrun = Overrun.Fail, exhausted = false)
      }
    }

    "Overrun.Fail: exhausted with no next step index once the script runs out" in {
      for {
        runner <- ScriptRunner.from(Script.exactly(reply("a")))
        _      <- runner.next
        status <- runner.status
      } yield status shouldBe ScriptStatus(totalSteps = 1, nextStepIndex = None, onOverrun = Overrun.Fail, exhausted = true)
    }

    "Overrun.RepeatLast: next step index stays at the final step, not exhausted" in {
      for {
        runner <- ScriptRunner.from(Script(List(reply("a"), reply("b")), Overrun.RepeatLast))
        _      <- runner.next
        _      <- runner.next
        status <- runner.status
      } yield status shouldBe ScriptStatus(totalSteps = 2, nextStepIndex = Some(1), onOverrun = Overrun.RepeatLast, exhausted = false)
    }

    "Overrun.Cycle: next step index wraps back to 0, not exhausted" in {
      for {
        runner <- ScriptRunner.from(Script(List(reply("a"), reply("b")), Overrun.Cycle))
        _      <- runner.next
        _      <- runner.next
        status <- runner.status
      } yield status shouldBe ScriptStatus(totalSteps = 2, nextStepIndex = Some(0), onOverrun = Overrun.Cycle, exhausted = false)
    }

    "reset brings the next step index back to 0" in {
      for {
        runner <- ScriptRunner.from(Script.exactly(reply("a")))
        _      <- runner.next
        _      <- runner.reset
        status <- runner.status
      } yield status shouldBe ScriptStatus(totalSteps = 1, nextStepIndex = Some(0), onOverrun = Overrun.Fail, exhausted = false)
    }
  }

  "dashboard routes" - {

    def clientFor(script: Script, journalMaxEntries: Int = CallJournal.DefaultMaxEntries,
                  scriptName: Option[String] = None): IO[Client[IO]] =
      App.build(script, journalMaxEntries, scriptName).map(Client.fromHttpApp)

    implicit val openAIReqEnc: EntityEncoder[IO, OpenAI.ChatRequest] =
      jsonEncoderOf[IO, OpenAI.ChatRequest]

    def openAIRequest(text: String = "hi"): Request[IO] =
      Request[IO](Method.POST, uri"/v1/chat/completions")
        .withEntity(OpenAI.ChatRequest("gpt-4o-mini", List(OpenAI.Message("user", Some(text)))))

    "GET /_llmsim/dashboard returns JSON with Cache-Control: no-store" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi")), scriptName = Some("com.example.Flow"))
        resp <- c.run(Request[IO](Method.GET, uri"/_llmsim/dashboard")).use { r =>
                  r.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value) shouldBe Some("no-store")
                  r.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value).getOrElse("") should
                    include ("application/json")
                  r.as[Json]
                }
      } yield {
        resp.hcursor.downField("script").downField("name").as[String] shouldBe Right("com.example.Flow")
        resp.hcursor.downField("schemaVersion").as[Int] shouldBe Right(1)
      }
    }

    "GET /_llmsim/ui returns the HTML page" in {
      for {
        c    <- clientFor(Script.exactly(reply("hi")))
        resp <- c.run(Request[IO](Method.GET, uri"/_llmsim/ui")).use { r =>
                  r.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value) shouldBe
                    Some("text/html; charset=utf-8")
                  r.bodyText.compile.string
                }
      } yield resp should include ("llmsim dashboard")
    }

    "the dashboard reflects only calls retained by a small-capacity journal" in {
      for {
        c     <- clientFor(Script.repeatingLast(reply("hi")), journalMaxEntries = 2)
        _     <- c.expect[String](openAIRequest())
        _     <- c.expect[String](openAIRequest())
        _     <- c.expect[String](openAIRequest())
        dash  <- c.expect[Json](Request[IO](Method.GET, uri"/_llmsim/dashboard"))
      } yield {
        // 3 calls made, capacity 2 -- only the 2 most recent are retained.
        dash.hcursor.downField("journal").downField("retainedCalls").as[Int] shouldBe Right(2)
        dash.hcursor.downField("journal").downField("capacity").as[Int] shouldBe Right(2)
      }
    }

    "DELETE /_llmsim/calls clears dashboard call counts but leaves script position alone" in {
      for {
        c      <- clientFor(Script.exactly(reply("a"), reply("b"), reply("c")))
        _      <- c.expect[String](openAIRequest())
        _      <- c.expect[String](Request[IO](Method.DELETE, uri"/_llmsim/calls"))
        dash   <- c.expect[Json](Request[IO](Method.GET, uri"/_llmsim/dashboard"))
      } yield {
        dash.hcursor.downField("journal").downField("retainedCalls").as[Int] shouldBe Right(0)
        // Script position untouched by DELETE -- next step is 1, not 0.
        dash.hcursor.downField("script").downField("nextStepIndex").as[Int] shouldBe Right(1)
      }
    }

    "POST /_llmsim/reset clears dashboard call counts AND rewinds script position" in {
      for {
        c      <- clientFor(Script.exactly(reply("a"), reply("b"), reply("c")))
        _      <- c.expect[String](openAIRequest())
        _      <- c.expect[String](Request[IO](Method.POST, uri"/_llmsim/reset"))
        dash   <- c.expect[Json](Request[IO](Method.GET, uri"/_llmsim/dashboard"))
      } yield {
        dash.hcursor.downField("journal").downField("retainedCalls").as[Int] shouldBe Right(0)
        dash.hcursor.downField("script").downField("nextStepIndex").as[Int] shouldBe Right(0)
      }
    }
  }
}
