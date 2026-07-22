package com.alai.llmsim.scripts

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/** Boots llmsim for ci/spring-verification (see VerificationTest there).
  * Nine steps, each consumed by exactly one test, in order, with no
  * warmup or discard calls:
  *   0. A plain text reply -- OpenAI-shaped basic ChatClient check.
  *   1. The same reply again -- Anthropic-shaped basic ChatClient check
  *      (its own step, since this design is one call per test).
  *   2. A tool call -- proves a tool_calls/tool_use block round-trips
  *      correctly through a real client. No tool is registered for this
  *      one on purpose: it only checks that Spring AI *parsed* the
  *      block, not that anything got executed.
  *   3. A reply carrying rate-limit-shaped headers for both providers --
  *      proves (or, for OpenAI, documents the current gap in) Spring
  *      AI's ChatResponseMetadata#getRateLimit().
  *   4. A second, distinct tool call, together with step 5 --
  *   5. -- a reply built from the REAL tool result: the non-streaming
  *      baseline round trip (llmsim returns a tool call, a real
  *      registered Java @Tool actually executes, its real return value
  *      comes back as the follow-up request's tool result, and this
  *      step answers from that). Establishing this before SSE means a
  *      streamed-tool-call failure later is clearly an SSE problem, not
  *      an ambiguous "is it the tool loop or the stream" one.
  *   6. A plain text reply, streamed -- OpenAI-shaped Flux<String> check.
  *   7. The same, streamed -- Anthropic-shaped Flux<String> check.
  *   8. A third, distinct tool call, streamed -- proves a streamed
  *      tool_calls block surfaces correctly once the stream completes.
  *      Like step 2, no tool registered: parsing only, not execution.
  *      A full streamed tool-callback round trip (mirroring steps 4-5,
  *      but over Flux) is deliberately not attempted yet -- that's the
  *      least-traveled code path in Spring AI's tool-calling advisor and
  *      belongs as its own follow-up once basic streaming is confirmed
  *      solid, not bundled into the same pass that first proves llmsim's
  *      streaming wire format works at all.
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
    ),
    toolCall(id = "call-2", name = "get_weather", arguments = """{"city":"Boston"}"""),
    replyFromToolResult("call-2")(result => s"Here's what the tool reported: $result"),
    reply("hello there world"),
    reply("hello there world"),
    toolCall(id = "call-3", name = "get_weather", arguments = """{"city":"Seattle"}""")
  )
}
