package com.alai.llmsim

import cats.effect.IO
import cats.syntax.semigroupk._
import org.http4s.{HttpApp, HttpRoutes, Uri}
import org.http4s.dsl.io._
import org.http4s.headers.{Location, Origin}
import org.http4s.implicits._
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.resourceServiceBuilder

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
        ManagementRoutes.routes(journal, runner, journalMaxEntries, scriptName) <+>
        consoleIndexRedirectRoutes <+>
        consoleRoutes).orNotFound
    )

  // resourceServiceBuilder doesn't perform directory-index resolution
  // -- confirmed directly by testing all three forms against a real
  // running server: /_llmsim/console/index.html loads, but both
  // /_llmsim/console and /_llmsim/console/ 404, even though the file
  // genuinely exists in the jar (also confirmed directly, by
  // extracting and listing the jar's contents). Explicit redirects for
  // the two friendly forms people will actually type, both pointing at
  // the one path that's confirmed to actually work.
  private val consoleIndexRedirectRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "_llmsim" / "console" =>
      TemporaryRedirect(Location(uri"/_llmsim/console/index.html"))
    case GET -> Root / "_llmsim" / "console" / "" =>
      TemporaryRedirect(Location(uri"/_llmsim/console/index.html"))
  }

  // Stage 1 of packaging the real Tyrian console (roadmap item 16)
  // into the server itself. Serves whatever's under classpath
  // resources at _llmsim/console/... at the SAME external URL prefix
  // -- basePath and pathPrefix are two genuinely separate settings on
  // ResourceServiceBuilder (basePath: classpath lookup location;
  // pathPrefix: the external URL prefix routes actually match against,
  // defaulting to "" if not set explicitly), confirmed from http4s's
  // own Config docs after basePath alone produced a real 404 -- the
  // routes were correctly finding files under _llmsim/console/ in the
  // classpath, but expecting requests at the root, not under
  // /_llmsim/console/ itself. A NEW path, not replacing the existing
  // /_llmsim/ui bare-bones dashboard -- that stays exactly as it is
  // until the Tyrian console is fully proven, a separate, later
  // decision.
  //
  // Classpath-resource static serving in an assembled fat jar (this
  // project's actual deployment shape, not `sbt run`) has a real,
  // documented history (http4s/http4s#299, a double-slash path-
  // normalization bug) -- from 2015, long before this project's
  // 0.23.27, and narrow even at the time (a specific prefix-handling
  // edge case, not "resources don't work in jars" the way the issue
  // title alone might suggest). Worth knowing that history exists;
  // not treated as a reason to expect it here.
  private val consoleRoutes: HttpRoutes[IO] =
    resourceServiceBuilder[IO]("/_llmsim/console")
      .withPathPrefix("/_llmsim/console")
      .toRoutes

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
