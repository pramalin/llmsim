package com.alai.llmsim

/** An explicit token count a script pins to a step, instead of relying on
  * llmsim's word-count heuristic. `promptTokens`/`completionTokens` map
  * directly onto both wire shapes: OpenAI's `usage.prompt_tokens` /
  * `usage.completion_tokens`, and Anthropic's `usage.input_tokens` /
  * `usage.output_tokens` -- see Simulator.scala's usage-resolution helpers.
  * Exists for scripts that need to test behavior at a specific token
  * count (a budget check, a context-window boundary) precisely, rather
  * than at whatever the heuristic happens to produce for that step's text.
  */
final case class UsageOverride(promptTokens: Int, completionTokens: Int)

/** A single call gets answered by one Step.
  *
  * `headers` lets a script attach arbitrary raw HTTP response headers --
  * most commonly OpenAI's `x-ratelimit-*` or Anthropic's
  * `anthropic-ratelimit-*` families, or `retry-after` on a 429. Raw
  * strings, not a structured rate-limit type: OpenAI's reset values are
  * its own compact duration format ("6m0s") and Anthropic's are RFC 3339
  * timestamps -- two genuinely different wire formats, and llmsim
  * shouldn't be the thing deciding how to translate between them any
  * more than it decides what a Reply's text should say. The script
  * author writes the exact value that goes on the wire.
  */
sealed trait Step
object Step {
  final case class Reply(text: String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty) extends Step
  final case class Error(status: Int, message: String, headers: Map[String, String] = Map.empty) extends Step

  /** The model requests a tool instead of replying with text. `arguments`
    * is a raw String, matching OpenAI's wire type exactly (it's a
    * JSON-encoded string there) -- see Protocol.scala for why, and for
    * the Anthropic-side asymmetry this creates.
    */
  final case class ToolCall(id: String, name: String, arguments: String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty) extends Step

  /** Builds its reply from the REAL tool result the app sends back in its
    * follow-up request, instead of a fixed string. llmsim never calls any
    * tool itself here -- it only reads the value the app already put in
    * its own request (from a real function call or its own MCP client),
    * exactly the same way the app would hand that value to a real LLM.
    * If no tool_result matching `toolCallId` is found, this fails loudly
    * rather than silently falling back to something misleading.
    */
  final case class ReplyFromToolResult(toolCallId: String, render: String => String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty) extends Step
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

  def reply(text: String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty): Step =
    Step.Reply(text, usage, headers)
  def error(status: Int, message: String, headers: Map[String, String] = Map.empty): Step =
    Step.Error(status, message, headers)
  def toolCall(id: String, name: String, arguments: String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty): Step =
    Step.ToolCall(id, name, arguments, usage, headers)
  def replyFromToolResult(toolCallId: String, usage: Option[UsageOverride] = None, headers: Map[String, String] = Map.empty)(render: String => String): Step =
    Step.ReplyFromToolResult(toolCallId, render, usage, headers)

  // So a script reads `reply("hi", usage = usage(promptTokens = 10, completionTokens = 20))`
  // instead of `Some(UsageOverride(10, 20))`.
  def usage(promptTokens: Int, completionTokens: Int): Option[UsageOverride] =
    Some(UsageOverride(promptTokens, completionTokens))
}

/** Every startup script is a Scala object implementing this trait. Main
  * loads one by fully-qualified object name (via the LLMSIM_SCRIPT env
  * var) and reflects out its `script` value -- see Main.scala.
  */
trait ScriptSource {
  def script: Script
}
