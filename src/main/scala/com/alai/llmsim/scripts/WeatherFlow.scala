package com.alai.llmsim.scripts

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/** An example of a fixed, ordered sequence: exactly three calls expected,
  * in this order. A fourth call gets a loud "script exhausted" error
  * instead of silently reusing the last reply -- useful for asserting
  * your app made exactly as many calls as you expected.
  *
  * Run with: LLMSIM_SCRIPT=com.alai.llmsim.scripts.WeatherFlow sbt run
  */
object WeatherFlow extends ScriptSource {
  val script: Script = Script.exactly(
    reply("Sure, let me check the weather."),
    reply("It looks like rain today."),
    reply("You're welcome!")
  )
}
