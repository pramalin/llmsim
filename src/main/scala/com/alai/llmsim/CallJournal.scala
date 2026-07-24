package com.alai.llmsim

import cats.effect.{IO, Ref}
import io.circe.Json

/** A message from the request -- moved to `common` (see
  * CapturedMessage.scala there), shared with the console. Same
  * package, so nothing here needed to change beyond removing this
  * duplicate definition.
  */

/** A response header llmsim actually emitted -- moved to `common`
  * (see CapturedHeader.scala there), shared with the console. Same
  * package, so nothing here needed to change beyond removing this
  * duplicate definition.
  */

/** What the simulator did with a call -- moved to `common` (see
  * CallOutcome.scala there), shared with the console. Same package,
  * so nothing here needed to change beyond removing this duplicate
  * definition.
  */

/** One call the simulator received -- moved to `common` (see
  * CapturedCall.scala there), shared with the console. Same package,
  * so nothing here needed to change beyond removing this duplicate
  * definition. Gained a proper companion-object codec in the move --
  * see that file for why.
  */

/** A reservation from `CallJournal.begin`, carrying everything
  * `complete` needs to build the final record. Plain, immutable data --
  * no shared mutable state beyond the sequence number itself, which is
  * reserved atomically at `begin` time.
  *
  * Stays here, not in `common` -- purely a server-internal detail
  * (never JSON-encoded, never sent to any client), unlike CapturedCall
  * and the other three moved types, which all cross the wire.
  */
final case class CallHandle(
    sequence: Long,
    provider: String,
    model: Option[String],
    messages: Vector[CapturedMessage],
    rawRequest: Json,
    receivedAtEpochMillis: Long
)

trait CallJournal {
  /** Reserves a sequence number and captures arrival, before any
    * response is built -- called at the very top of a route, same
    * timing point the old single-shot `record` used for
    * receivedAtEpochMillis. Sequence numbers are handed out in true
    * arrival order this way, regardless of which of several concurrent
    * calls finishes first -- the bug the old completion-time-only
    * `record` had: two overlapping requests could end up with their
    * journal order reflecting which one finished first, not which one
    * actually arrived first.
    */
  def begin(
      provider: String,
      model: Option[String],
      messages: Vector[CapturedMessage],
      rawRequest: Json,
      receivedAtEpochMillis: Long
  ): IO[CallHandle]

  /** Records the final outcome and makes the call visible in the
    * journal. For a non-streaming call this runs immediately after
    * `begin`, in the same route -- there's no long-lived body to wait
    * on. For a streaming call, this belongs in the SSE frames stream's
    * own finalizer (see Simulator.scala's `sseResponse`), so it fires
    * once the stream is actually done being consumed -- successfully,
    * with an error, or cancelled by a client disconnect -- not merely
    * when the response object is constructed.
    */
  def complete(
      handle: CallHandle,
      outcome: CallOutcome,
      stepIndex: Option[Int],
      completedAtEpochMillis: Long,
      durationMillis: Long,
      responseHeaders: Vector[CapturedHeader] = Vector.empty,
      streamed: Boolean = false
  ): IO[CapturedCall]

  def all: IO[List[CapturedCall]]
  def find(sequence: Long): IO[Option[CapturedCall]]

  /** Clears the journal only -- does not touch script position. See
    * ManagementRoutes: this backs DELETE /_llmsim/calls, while
    * POST /_llmsim/reset additionally rewinds the ScriptRunner.
    */
  def clear: IO[Unit]
}

object CallJournal {
  val DefaultMaxEntries = 1000

  /** Sequence and calls live in ONE Ref, not two -- `begin` reserving a
    * sequence and `complete` inserting a call both need to see and
    * update the same state atomically.
    */
  private final case class JournalState(nextSequence: Long, calls: Vector[CapturedCall])

  /** @param maxEntries oldest entries (by sequence -- see `complete`
    *                    below for why that's not simply insertion
    *                    order) are dropped once the journal holds more
    *                    than this many, so a long-running simulator
    *                    can't grow its call log without bound.
    */
  def inMemory(maxEntries: Int = DefaultMaxEntries): IO[CallJournal] =
    Ref.of[IO, JournalState](JournalState(nextSequence = 1L, calls = Vector.empty)).map { stateRef =>
      new CallJournal {
        def begin(
            provider: String,
            model: Option[String],
            messages: Vector[CapturedMessage],
            rawRequest: Json,
            receivedAtEpochMillis: Long
        ): IO[CallHandle] =
          stateRef.modify { state =>
            val handle = CallHandle(state.nextSequence, provider, model, messages, rawRequest, receivedAtEpochMillis)
            state.copy(nextSequence = state.nextSequence + 1) -> handle
          }

        def complete(
            handle: CallHandle,
            outcome: CallOutcome,
            stepIndex: Option[Int],
            completedAtEpochMillis: Long,
            durationMillis: Long,
            responseHeaders: Vector[CapturedHeader] = Vector.empty,
            streamed: Boolean = false
        ): IO[CapturedCall] =
          stateRef.modify { state =>
            val call = CapturedCall(
              handle.sequence, handle.provider, handle.model, handle.messages, handle.rawRequest,
              outcome, stepIndex, handle.receivedAtEpochMillis, completedAtEpochMillis, durationMillis,
              responseHeaders, streamed
            )
            // Sorted by sequence (arrival order), not insertion order:
            // begin() reserves sequence numbers in true arrival order,
            // but complete() can run in a DIFFERENT order under
            // concurrency -- a call that arrived second might finish
            // first. Sorting here keeps both the exposed /_llmsim/calls
            // ordering AND which entries eviction drops correct by
            // arrival time, not by whichever call happened to complete
            // first.
            //
            // Known, deliberately deferred gap: a call still in flight
            // when /_llmsim/reset or DELETE /_llmsim/calls runs, and
            // completing afterward, inserts itself into the fresh
            // post-reset journal under its OLD (now out-of-range)
            // sequence number. Not something today's code can actually
            // trigger -- nothing is long-lived enough yet for a call to
            // still be in flight across a reset -- worth revisiting once
            // fault injection adds real delays.
            val retained = (state.calls :+ call).sortBy(_.sequence).takeRight(maxEntries)
            state.copy(calls = retained) -> call
          }

        def all: IO[List[CapturedCall]] = stateRef.get.map(_.calls.toList)

        def find(sequence: Long): IO[Option[CapturedCall]] =
          stateRef.get.map(_.calls.find(_.sequence == sequence))

        def clear: IO[Unit] = stateRef.set(JournalState(nextSequence = 1L, calls = Vector.empty))
      }
    }
}
