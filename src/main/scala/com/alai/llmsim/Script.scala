package com.alai.llmsim

/** A single call gets answered by one Step. Kept deliberately small for
  * now — ToolUse (tool_use / tool_result round trips) is the next rung,
  * not this one.
  */
sealed trait Step
object Step {
  final case class Reply(text: String) extends Step
  final case class Error(status: Int, message: String) extends Step
}

/** What happens on the call AFTER the script's last step. There is no
  * default — a Script must state one, because a silent fallback here is
  * exactly the ambiguity that ruled out the old "active scenario" idea.
  */
sealed trait Overrun
object Overrun {
  case object Fail       extends Overrun // next call after the end -> simulator errors loudly
  case object RepeatLast extends Overrun // keep replaying the final step forever
  case object Cycle      extends Overrun // loop back to the first step
}

final case class Script(steps: List[Step], onOverrun: Overrun) {
  require(steps.nonEmpty, "a Script must contain at least one step")
}

object Script {
  // Three named constructors -- there is deliberately no `Script(steps)`
  // with an implicit default, so writing a script forces a choice.
  def exactly(steps: Step*): Script       = Script(steps.toList, Overrun.Fail)
  def repeatingLast(steps: Step*): Script = Script(steps.toList, Overrun.RepeatLast)
  def cycling(steps: Step*): Script       = Script(steps.toList, Overrun.Cycle)

  def reply(text: String): Step               = Step.Reply(text)
  def error(status: Int, message: String): Step = Step.Error(status, message)
}

/** Every startup script is a Scala object implementing this trait. Main
  * loads one by fully-qualified object name (via the LLMSIM_SCRIPT env
  * var) and reflects out its `script` value -- see Main.scala.
  */
trait ScriptSource {
  def script: Script
}
