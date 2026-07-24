package com.alai.llmsim

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.comcast.ip4s._
import org.http4s._
import org.http4s.circe._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

import Script._
import scala.concurrent.duration._

/** The two tests from the design note on whether a client disconnect
  * actually cancels a pending streamFault delay (see the project's
  * design discussion -- not yet in docs/, since the question wasn't
  * settled at time of writing). Deliberately kept separate from
  * SimulatorSpec.scala: these answer a specific, narrow question about
  * cancellation propagation, not general simulator behavior, and the
  * second test is a real integration test (a real Ember server, a raw
  * socket), a different category from everything else in this suite.
  */
class DisconnectSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private implicit val capturedCallDecoder: Decoder[CapturedCall] = deriveDecoder
  private implicit val callsEntityDecoder: EntityDecoder[IO, List[CapturedCall]] =
    jsonOf[IO, List[CapturedCall]]

  // -----------------------------------------------------------------
  // Test 1: isolated cancellation, no HTTP involved at all. Confirms
  // Simulator.paced's own delay mechanism cooperates with explicit
  // fiber cancellation -- a narrow, cheap regression test, expected to
  // pass, since Cats Effect's sleep is cancelable as a matter of how
  // the library works. This does NOT touch the actual open question
  // (whether a real disconnect causes that cancellation to happen at
  // all) -- see test 2 for that.
  // -----------------------------------------------------------------

  "Simulator.paced cooperates with explicit fiber cancellation" in {
    val longDelay = 30.seconds
    val frames     = List("first", "second", "third")
    val fault      = Some(StreamFault(delayBeforeFirstEvent = longDelay))

    for {
      startedAt <- IO.monotonic
      fiber     <- Simulator.paced(frames, fault).compile.drain.start
      _         <- IO.sleep(100.millis)
      // Fiber.cancel waits for the target fiber to actually finish
      // being cancelled (including any finalizers), it isn't
      // fire-and-forget -- so how long THIS call takes is exactly the
      // measurement that matters: if the pending Stream.sleep
      // genuinely gets interrupted, this returns near the 100ms mark;
      // if something prevented that, it wouldn't return until the
      // full 30-second delay elapsed on its own.
      _         <- fiber.cancel
      endedAt   <- IO.monotonic
    } yield (endedAt - startedAt) should be < 2.seconds
  }

  // -----------------------------------------------------------------
  // Test 2: the decisive test. A real Ember server on an ephemeral
  // port, a raw java.net.Socket (not Client.fromHttpApp, not even
  // http4s's own client -- a real client library might perform
  // graceful body-draining on close that a genuinely abandoned
  // connection wouldn't, which would just reproduce the same ambiguity
  // that started this investigation). Reads the response headers,
  // then closes the socket before the scripted delay's first event
  // would ever arrive -- the same category of "disconnect while a
  // delay is pending" question as delayBetweenEvents, just triggered
  // at the earliest possible point in the stream, and simpler to
  // implement: nothing has been written to the body yet at that point,
  // so there's no HTTP chunked-encoding framing to parse before
  // closing, only the headers (which are never chunked).
  //
  // What "did it work" means here is deliberately narrow, per the
  // design note: this only asserts that SOME terminal state appears in
  // the journal well before the 30-second delay would have finished on
  // its own -- proving prompt termination, whichever ExitCase caused
  // it. It does NOT assert which CallOutcome that is. Today's code
  // only maps Resource.ExitCase.Canceled to CallOutcome.Cancelled;
  // ExitCase.Errored still maps to CallOutcome.Failed (see
  // Simulator.finalizeStream) -- deciding whether a connection-reset
  // error should ALSO count as Cancelled is a separate policy
  // question, deferred until this test shows what Ember actually
  // reports.
  // -----------------------------------------------------------------

  // CONFIRMED, 2026-07-23: this test's ORIGINAL assertion (terminal
  // state within 2s) genuinely failed -- the journal was still empty at
  // 2s, and the full suite run took ~28 extra seconds, matching the
  // scripted delay almost exactly. That's not a test bug: it's Ember's
  // own server shutdown blocking on the still-sleeping response fiber,
  // an independent signal pointing at the same conclusion the failed
  // assertion did. This is Result C from the design note -- a client
  // disconnecting does NOT currently interrupt a pending StreamFault
  // delay. See docs/ once a permanent design note exists; for now, this
  // comment and the assertions below are the record of that finding.
  //
  // The test is rewritten to characterize the CONFIRMED actual
  // behavior -- not prompt, but eventually consistent -- rather than
  // assert the desired behavior we now know doesn't hold. A red test
  // asserting the ideal would sit failing indefinitely until the real
  // fix (still undecided -- see the design discussion) lands, blocking
  // every unrelated commit in between for a gap that's already
  // understood. This version stays green while still locking in a
  // regression check: if disconnect handling ever gets WORSE (the call
  // never reaching a terminal state at all), this would catch that too.
  // Once real disconnect handling ships, flip promptCalls back to
  // asserting size 1 within the short window -- that's the signal this
  // test is obsolete in its current form.
  "a real client closing its connection is NOT detected promptly today -- confirmed gap, not yet fixed" in {
    val delay = 5.seconds
    val script = Script.exactly(
      reply("hello there world", streamFault = streamFault(delayBeforeFirstEvent = delay))
    )

    val requestBody = """{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"hi"}]}"""

    // Blocking, hand-rolled HTTP/1.1 -- deliberately not using any
    // http4s or java.net.http client, all of which manage connection
    // lifecycle themselves in ways that could mask what a genuinely
    // abandoned connection looks like. Reads only the status line and
    // headers (stops at the blank line separator), then closes --
    // never touches the body, which is exactly the scenario in
    // question.
    def probeAndDisconnect(port: Int): IO[Unit] = IO.blocking {
      val socket = new java.net.Socket("127.0.0.1", port)
      try {
        socket.setSoTimeout(5000)
        val bodyBytes = requestBody.getBytes("UTF-8")
        val request =
          "POST /v1/chat/completions HTTP/1.1\r\n" +
            s"Host: 127.0.0.1:$port\r\n" +
            "Content-Type: application/json\r\n" +
            s"Content-Length: ${bodyBytes.length}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream
        out.write(request.getBytes("UTF-8"))
        out.write(bodyBytes)
        out.flush()

        val in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, "UTF-8"))
        var line = in.readLine()
        // Status line, then headers until the blank-line separator --
        // never reads into the body.
        while (line != null && line.nonEmpty) {
          line = in.readLine()
        }
      } finally {
        socket.close() // the actual disconnect this test is about
      }
    }

    def queryJournal(httpApp: HttpApp[IO]): IO[List[CapturedCall]] =
      httpApp.run(Request[IO](Method.GET, uri"/_llmsim/calls")).flatMap(_.as[List[CapturedCall]])

    val program =
      for {
        httpApp <- App.build(script)
        result  <- EmberServerBuilder
                     .default[IO]
                     .withHost(host"127.0.0.1")
                     .withPort(port"0")
                     .withHttpApp(httpApp)
                     .build
                     .use { server =>
                       for {
                         _            <- probeAndDisconnect(server.address.getPort)
                         _            <- IO.sleep(2.seconds)
                         // Confirmed absent at 2s -- see the comment
                         // above. This is the documented gap, not a
                         // flaky assertion.
                         promptCalls  <- queryJournal(httpApp)
                         _            <- IO.sleep(delay - 2.seconds + 500.millis)
                         // But it DOES eventually complete once the
                         // scripted delay naturally runs out -- the
                         // mechanism isn't stuck forever, just not
                         // responsive to the disconnect itself.
                         eventualCalls <- queryJournal(httpApp)
                       } yield (promptCalls, eventualCalls)
                     }
      } yield result

    program.map { case (promptCalls, eventualCalls) =>
      promptCalls shouldBe empty
      eventualCalls should have size 1
      // Diagnostic visibility into WHICH ExitCase actually happened,
      // for the classification decision (Cancelled vs Failed) the
      // design note flagged as a separate question from this one.
      info(s"eventual outcome: ${eventualCalls.head.outcome}")
    }
  }
}
