package com.alai.llmsim

/** A single call gets answered by one Step. */
sealed trait Step
object Step {
  final case class Reply(text: String) extends Step
  final case class Error(status: Int, message: String) extends Step

  /** The model requests a tool instead of replying with text. `arguments`
    * is a raw String, matching OpenAI's wire type exactly (it's a
    * JSON-encoded string there) -- see Protocol.scala for why, and for
    * the Anthropic-side asymmetry this creates.
    */
  final case class ToolCall(id: String, name: String, arguments: String) extends Step

  /** Builds its reply from the REAL tool result the app sends back in its
    * follow-up request, instead of a fixed string. llmsim never calls any
    * tool itself here -- it only reads the value the app already put in
    * its own request (from a real function call or its own MCP client),
    * exactly the same way the app would hand that value to a real LLM.
    * If no tool_result matching `toolCallId` is found, this fails loudly
    * rather than silently falling back to something misleading.
    */
  final case class ReplyFromToolResult(toolCallId: String, render: String => String) extends Step
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
  def toolCall(id: String, name: String, arguments: String): Step = Step.ToolCall(id, name, arguments)
  def replyFromToolResult(toolCallId: String)(render: String => String): Step =
    Step.ReplyFromToolResult(toolCallId, render)
}

/** Every startup script is a Scala object implementing this trait. Main
  * loads one by fully-qualified object name (via the LLMSIM_SCRIPT env
  * var) and reflects out its `script` value -- see Main.scala.
  */
trait ScriptSource {
  def script: Script
}
