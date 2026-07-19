package llmsim.scripts

import llmsim.{Script, ScriptSource}
import llmsim.Script._

/** The out-of-the-box default: answers every call with the same reply,
  * forever. Used when LLMSIM_SCRIPT isn't set. Point at a different
  * object (see WeatherFlow below) to test an actual multi-call sequence.
  */
object Default extends ScriptSource {
  val script: Script = Script.repeatingLast(
    reply("This is a simulated response.")
  )
}
