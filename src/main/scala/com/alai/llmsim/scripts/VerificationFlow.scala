package com.alai.llmsim.scripts

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/** Boots llmsim for ci/spring-verification (see VerificationTest there).
  * Four steps, each consumed by exactly one test, in order, with no
  * warmup or discard calls:
  *   0. A plain text reply -- OpenAI-shaped basic ChatClient check.
  *   1. The same reply again -- Anthropic-shaped basic ChatClient check
  *      (its own step, since this design is one call per test).
  *   2. A tool call -- proves a tool_calls/tool_use block round-trips
  *      correctly through a real client.
  *   3. A reply carrying rate-limit-shaped headers for both providers --
  *      proves (or, for OpenAI, documents the current gap in) Spring
  *      AI's ChatResponseMetadata#getRateLimit().
  *
  * Kept in llmsim's own scripts package, not the verification module,
  * since Main loads scripts by fully-qualified name off llmsim's own
  * classpath -- see the README's "Writing a script" section.
  */
object VerificationFlow extends ScriptSource {
  val script: Script = Script.exactly(
    reply("hello there world"),
    reply("hello there world"),
    toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"San Francisco"}"""),
    reply(
      "rate limited example",
      headers = Map(
        "x-ratelimit-limit-requests"              -> "60",
        "x-ratelimit-remaining-requests"           -> "59",
        "x-ratelimit-reset-requests"               -> "1s",
        "anthropic-ratelimit-requests-limit"       -> "1000",
        "anthropic-ratelimit-requests-remaining"   -> "999",
        "anthropic-ratelimit-requests-reset"       -> "2026-07-21T19:00:00Z"
      )
    )
  )
}
