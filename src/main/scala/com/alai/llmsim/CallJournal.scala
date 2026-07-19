package com.alai.llmsim

import cats.effect.{IO, Ref}
import io.circe.Json

/** One call the simulator answered.
  *
  * `stepIndex` is which script step answered it (0-based), or `None` if
  * the script was already exhausted when this call arrived. `request` is
  * the raw JSON body exactly as received -- not our parsed case classes
  * -- so the test harness can inspect anything the app sent, including
  * fields llmsim doesn't itself model.
  */
final case class CapturedCall(
    sequence: Long,
    provider: String,
    request: Json,
    stepIndex: Option[Int],
    receivedAtEpochMillis: Long
)

trait CallJournal {
  def record(provider: String, request: Json, stepIndex: Option[Int]): IO[CapturedCall]
  def all: IO[List[CapturedCall]]
  def reset: IO[Unit]
}

object CallJournal {
  def inMemory: IO[CallJournal] =
    for {
      callsRef <- Ref.of[IO, List[CapturedCall]](Nil)
      seqRef   <- Ref.of[IO, Long](0)
    } yield new CallJournal {
      def record(provider: String, request: Json, stepIndex: Option[Int]): IO[CapturedCall] =
        for {
          seq  <- seqRef.updateAndGet(_ + 1)
          call =  CapturedCall(seq, provider, request, stepIndex, System.currentTimeMillis())
          _    <- callsRef.update(_ :+ call)
        } yield call

      def all: IO[List[CapturedCall]] = callsRef.get

      def reset: IO[Unit] =
        for {
          _ <- callsRef.set(Nil)
          _ <- seqRef.set(0)
        } yield ()
    }
}
