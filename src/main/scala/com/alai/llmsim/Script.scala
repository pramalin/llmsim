package com.alai.llmsim

import scala.concurrent.duration.{Duration, FiniteDuration}

/** An explicit token count a script pins to a step, instead of relying on
  * llmsim's word-count heuristic. `promptTokens`/`completionTokens` map
  * directly onto both wire shapes: OpenAI's `usage.prompt_tokens` /
  * `usage.completion_tokens`, and Anthropic's `usage.input_tokens` /
  * `usage.output_tokens` -- see Simulator.scala's usage-resolution helpers.
  * Exists for scripts that need to test behavior at a specific token
  * count (a budget check, a context-window boundary) precisely, rather
  * than at whatever the heuristic happens to produce for that step's text.
  */
final case class UsageOverride(promptTokens: Int, completionTokens: Int) {
  require(promptTokens >= 0, s"promptTokens must be >= 0, got $promptTokens")
  require(completionTokens >= 0, s"completionTokens must be >= 0, got $completionTokens")
}

/** Artificial timing a script can attach to a streamed response --
  * ignored entirely for a non-streaming call to the same step, since
  * these are transport-level effects with nothing meaningful to mean
  * outside SSE.
  *
  * Only delays for now (roadmap item 14's first stage); disconnect,
  * malformed events, an omitted completion event, and split tool-call
  * arguments are the same StreamFault case class's later fields, not a
  * new type -- adding fields here as each one lands keeps every
  * existing script (which never mentions StreamFault at all) compiling
  * unchanged throughout.
  */
final case class StreamFault(
    // Silence before the FIRST frame goes out at all (including the
    // role-announcing delta) -- a stand-in for real "time to first
    // token" latency.
    delayBeforeFirstChunk: Option[FiniteDuration] = None,
    // Silence before every frame AFTER the first, applied uniformly --
    // including before the terminal frame (OpenAI's literal [DONE], or
    // Anthropic's message_stop) -- a stand-in for a model that's slow
    // to keep generating, not just slow to start.
    delayBetweenChunks: Option[FiniteDuration] = None
) {
  delayBeforeFirstChunk.foreach(d => require(d >= Duration.Zero, s"delayBeforeFirstChunk must be >= 0, got $d"))
  delayBetweenChunks.foreach(d => require(d >= Duration.Zero, s"delayBetweenChunks must be >= 0, got $d"))
}

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
  final case class Reply(
      text: String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  ) extends Step
  final case class Error(status: Int, message: String, headers: Map[String, String] = Map.empty) extends Step

  /** The model requests a tool instead of replying with text. `arguments`
    * is a raw String, matching OpenAI's wire type exactly (it's a
    * JSON-encoded string there) -- see Protocol.scala for why, and for
    * the Anthropic-side asymmetry this creates.
    */
  final case class ToolCall(
      id: String,
      name: String,
      arguments: String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  ) extends Step

  /** Builds its reply from the REAL tool result the app sends back in its
    * follow-up request, instead of a fixed string. llmsim never calls any
    * tool itself here -- it only reads the value the app already put in
    * its own request (from a real function call or its own MCP client),
    * exactly the same way the app would hand that value to a real LLM.
    * If no tool_result matching `toolCallId` is found, this fails loudly
    * rather than silently falling back to something misleading.
    */
  final case class ReplyFromToolResult(
      toolCallId: String,
      render: String => String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  ) extends Step
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

  def reply(
      text: String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  ): Step =
    Step.Reply(text, usage, headers, streamFault)
  def error(status: Int, message: String, headers: Map[String, String] = Map.empty): Step =
    Step.Error(status, message, headers)
  def toolCall(
      id: String,
      name: String,
      arguments: String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  ): Step =
    Step.ToolCall(id, name, arguments, usage, headers, streamFault)
  def replyFromToolResult(
      toolCallId: String,
      usage: Option[UsageOverride] = None,
      headers: Map[String, String] = Map.empty,
      streamFault: Option[StreamFault] = None
  )(render: String => String): Step =
    Step.ReplyFromToolResult(toolCallId, render, usage, headers, streamFault)

  // So a script reads `reply("hi", usage = usage(promptTokens = 10, completionTokens = 20))`
  // instead of `Some(UsageOverride(10, 20))`.
  def usage(promptTokens: Int, completionTokens: Int): Option[UsageOverride] =
    Some(UsageOverride(promptTokens, completionTokens))

  // Same convenience pattern as usage(...) above: `reply("hi", streamFault =
  // streamFault(delayBeforeFirstChunk = Some(2.seconds)))` instead of
  // spelling out Some(StreamFault(...)) by hand. Only ever has an
  // observable effect on a streamed call to the same step -- see
  // StreamFault's own doc comment.
  def streamFault(
      delayBeforeFirstChunk: Option[FiniteDuration] = None,
      delayBetweenChunks: Option[FiniteDuration] = None
  ): Option[StreamFault] =
    Some(StreamFault(delayBeforeFirstChunk, delayBetweenChunks))
}

/** Every startup script is a Scala object implementing this trait. Main
  * loads one by fully-qualified object name (via the LLMSIM_SCRIPT env
  * var) and reflects out its `script` value -- see Main.scala.
  */
trait ScriptSource {
  def script: Script
}
