package example

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/** Used only by the release workflow's consumer smoke test (see
  * .github/workflows/publish.yml). Proves the documented Pattern A
  * extension mechanism -- README: "Using llmsim in an app's end-to-end
  * tests" -- actually still works against the image about to be
  * published, not just that llmsim's own code compiles. Deliberately
  * lives under a `package example` unrelated to llmsim's own package, the
  * same way a real consuming project's script would.
  */
object TestFlow extends ScriptSource {
  val script: Script = Script.repeatingLast(reply("smoke test ok"))
}
