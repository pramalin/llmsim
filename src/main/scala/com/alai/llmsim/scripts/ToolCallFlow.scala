package com.alai.llmsim.scripts

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/** An example tool-call round trip: the model requests a tool on the
  * first call, then builds its reply from whatever real result the app
  * sends back on the second call -- llmsim never calls the tool itself,
  * it just reads the value the app already put in its own request.
  *
  * Run with: LLMSIM_SCRIPT=com.alai.llmsim.scripts.ToolCallFlow sbt run
  */
object ToolCallFlow extends ScriptSource {
  val script: Script = Script.exactly(
    toolCall(id = "call-1", name = "get_weather", arguments = """{"city":"San Francisco"}"""),
    replyFromToolResult("call-1")(result => s"Here's what the tool reported: $result")
  )
}
