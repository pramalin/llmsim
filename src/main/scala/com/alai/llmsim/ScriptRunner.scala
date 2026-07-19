package com.alai.llmsim

import cats.effect.{IO, Ref}

/** Outcome of asking the runner for the next step. */
sealed trait NextStep
object NextStep {
  final case class Answer(step: Step) extends NextStep
  case object Exhausted extends NextStep
}

trait ScriptRunner {
  def next: IO[NextStep]
}

object ScriptRunner {
  def from(script: Script): IO[ScriptRunner] =
    Ref.of[IO, Int](0).map { positionRef =>
      new ScriptRunner {
        def next: IO[NextStep] =
          positionRef.modify { i =>
            val steps = script.steps
            if (i < steps.length) {
              (i + 1, NextStep.Answer(steps(i)))
            } else {
              script.onOverrun match {
                case Overrun.Fail       => (i, NextStep.Exhausted)
                case Overrun.RepeatLast => (i, NextStep.Answer(steps.last))
                case Overrun.Cycle      => (1, NextStep.Answer(steps.head))
              }
            }
          }
      }
    }
}
