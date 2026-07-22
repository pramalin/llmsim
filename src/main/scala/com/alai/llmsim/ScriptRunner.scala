package com.alai.llmsim

import cats.effect.{IO, Ref}

/** Outcome of asking the runner for the next step. `Answer` carries the
  * (0-based) index of the step that answered, so callers can record it --
  * see CallJournal.
  */
sealed trait NextStep
object NextStep {
  final case class Answer(step: Step, index: Int) extends NextStep
  case object Exhausted extends NextStep
}

/** A read-only snapshot of where the runner stands, for things like the
  * dashboard (see Dashboard.scala) that want to show script progress
  * without touching it.
  *
  * Deliberately NOT just the raw internal position counter: that value
  * is genuinely ambiguous from outside without also knowing the
  * script's overrun policy. Once a script is exhausted, the internal
  * position freezes at `totalSteps` for BOTH Fail and RepeatLast --
  * there's no way to tell those two apart from the number alone -- and
  * for Cycle it never even stays there, since the position is corrected
  * back to 1 within the same atomic step that answers the wraparound
  * call. `nextStepIndex`/`exhausted` give the actual meaningful
  * interpretation instead of a leaky implementation detail:
  *   - still running:  nextStepIndex = Some(i), exhausted = false
  *   - Fail, exhausted:       nextStepIndex = None,             exhausted = true
  *   - RepeatLast, exhausted: nextStepIndex = Some(totalSteps-1), exhausted = false
  *   - Cycle, exhausted:      nextStepIndex = Some(0),             exhausted = false
  */
final case class ScriptStatus(
    totalSteps: Int,
    nextStepIndex: Option[Int],
    onOverrun: Overrun,
    exhausted: Boolean
)

trait ScriptRunner {
  def next: IO[NextStep]
  def reset: IO[Unit]
  def status: IO[ScriptStatus]
}

object ScriptRunner {
  def from(script: Script): IO[ScriptRunner] =
    Ref.of[IO, Int](0).map { positionRef =>
      new ScriptRunner {
        def next: IO[NextStep] =
          positionRef.modify { i =>
            val steps = script.steps
            if (i < steps.length) {
              (i + 1, NextStep.Answer(steps(i), i))
            } else {
              script.onOverrun match {
                case Overrun.Fail       => (i, NextStep.Exhausted)
                case Overrun.RepeatLast => (i, NextStep.Answer(steps.last, steps.length - 1))
                case Overrun.Cycle      => (1, NextStep.Answer(steps.head, 0))
              }
            }
          }

        def reset: IO[Unit] = positionRef.set(0)

        def status: IO[ScriptStatus] =
          positionRef.get.map { i =>
            val total = script.steps.length
            val (nextIdx, exhausted) =
              if (i < total) (Some(i), false)
              else script.onOverrun match {
                case Overrun.Fail       => (None, true)
                case Overrun.RepeatLast => (Some(total - 1), false)
                case Overrun.Cycle      => (Some(0), false)
              }
            ScriptStatus(total, nextIdx, script.onOverrun, exhausted)
          }
      }
    }
}
