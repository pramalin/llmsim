package com.alai.llmsim

import cats.effect.IO
import cats.syntax.semigroupk._
import org.http4s.{HttpApp, Uri}
import org.http4s.headers.Origin
import org.http4s.implicits._
import org.http4s.server.middleware.CORS

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
    } yield withDevCors(
      (Simulator.routes(runner, journal) <+>
        ManagementRoutes.routes(journal, runner, journalMaxEntries, scriptName)).orNotFound
    )

  // Dev-only convenience, off unless explicitly requested: in
  // production the console is served by llmsim itself (same origin,
  // per docs/console-framework-decision.md's own plan), so CORS never
  // needs to be on for a real deployment -- only while developing
  // console-tyrian against a separate Vite dev server on a different
  // port. Set LLMSIM_DEV_CORS (to anything) to opt in; unset by
  // default, so every existing deployment is completely unaffected.
  //
  // Uses the CURRENT http4s CORS API (CORS.policy.withAllowOriginHost,
  // confirmed against a real, working EmberServerBuilder-based example
  // using the exact same .orNotFound pattern this project already
  // uses) -- not the older CORS(service)/CORSConfig API, which
  // http4s's own security advisory (GHSA-52cf-226f-rhr6) found
  // vulnerable to an origin-reflection attack and deprecated as a
  // result. Deliberately narrow (one hardcoded origin, matching Vite's
  // own default port) rather than accepting an arbitrary
  // caller-supplied origin string to allow -- avoids reintroducing the
  // same class of problem that advisory was about.
  private def withDevCors(app: HttpApp[IO]): HttpApp[IO] =
    if (sys.env.contains("LLMSIM_DEV_CORS"))
      CORS.policy
        .withAllowOriginHost(Set(Origin.Host(Uri.Scheme.http, Uri.RegName("localhost"), Some(5173))))
        .apply(app)
    else app
}
