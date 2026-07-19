package com.alai.llmsim

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder

/** Run with `sbt run`, or `LLMSIM_SCRIPT=com.alai.llmsim.scripts.WeatherFlow sbt run`
  * to boot with a different script. There is no way to change the script
  * once the simulator is running -- that's intentional: configuration is
  * a startup-time decision, not something the traffic being served can
  * ever influence.
  *
  * Point any OpenAI- or Anthropic-compatible client at
  * http://localhost:8089 instead of the real API host, e.g.:
  *
  *   OPENAI_BASE_URL=http://localhost:8089/v1  (for OpenAI-client libraries)
  *   ANTHROPIC_BASE_URL=http://localhost:8089  (for Anthropic-client libraries)
  */
object Main extends IOApp.Simple {

  private val DefaultScriptClass = "com.alai.llmsim.scripts.Default"

  private def loadScript(fullyQualifiedObjectName: String): IO[Script] = IO {
    // Scala objects compile to a class named "Name$" with a static
    // MODULE$ field holding the singleton instance.
    val moduleClass = Class.forName(fullyQualifiedObjectName + "$")
    val instance    = moduleClass.getField("MODULE$").get(null)
    instance match {
      case source: ScriptSource => source.script
      case other =>
        throw new IllegalArgumentException(
          s"$fullyQualifiedObjectName must extend com.alai.llmsim.ScriptSource " +
            s"(found: ${other.getClass.getName})"
        )
    }
  }

  def run: IO[Unit] =
    for {
      className <- IO(sys.env.getOrElse("LLMSIM_SCRIPT", DefaultScriptClass))
      script    <- loadScript(className)
      _         <- IO.println(s"llmsim: booting with script '$className' (${script.steps.size} step(s), onOverrun=${script.onOverrun})")
      httpApp   <- App.build(script)
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(host"0.0.0.0")
             .withPort(port"8089")
             .withHttpApp(httpApp)
             .build
             .useForever
    } yield ()
}
