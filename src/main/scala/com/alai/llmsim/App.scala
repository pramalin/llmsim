package com.alai.llmsim

import cats.effect.IO
import cats.syntax.semigroupk._
import org.http4s.HttpApp
import org.http4s.implicits._

/** Builds the full HttpApp for a given Script: a fresh ScriptRunner and
  * CallJournal, with the vendor-shaped simulator routes and the
  * test-harness management routes combined into one app. Both Main and
  * the test suites build the app through here, so there's one place that
  * decides how those two route sets fit together.
  *
  * `scriptName` is purely cosmetic (shown on the dashboard so it's
  * obvious at a glance which script is actually loaded -- a real
  * mistake this project has made more than once), so it defaults to
  * `None` rather than being required; every existing `App.build(script)`
  * call site (all of them, in tests) keeps compiling unchanged.
  */
object App {
  def build(
      script: Script,
      journalMaxEntries: Int = CallJournal.DefaultMaxEntries,
      scriptName: Option[String] = None
  ): IO[HttpApp[IO]] =
    for {
      runner  <- ScriptRunner.from(script)
      journal <- CallJournal.inMemory(journalMaxEntries)
    } yield (Simulator.routes(runner, journal) <+>
      ManagementRoutes.routes(journal, runner, journalMaxEntries, scriptName)).orNotFound
}
