ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "com.alai"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.27"
val circeVersion  = "0.14.9"

// Lets vite.config.js ask sbt directly for where Scala.js's output
// actually landed, rather than hardcoding a path that includes the
// Scala version -- github.com/zetashift/tyrian-vite-tailwindcss-example.
val fastLinkOutputDir = taskKey[String]("output directory for `npm run dev`")
val fullLinkOutputDir = taskKey[String]("output directory for `npm run build`")

lazy val root = (project in file("."))
  .settings(
    name := "llmsim",
    Compile / mainClass := Some("com.alai.llmsim.Main"),
    assembly / mainClass := Some("com.alai.llmsim.Main"),
    assembly / assemblyJarName := "llmsim.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class"           => MergeStrategy.discard
      case "reference.conf"              => MergeStrategy.concat
      case _                             => MergeStrategy.first
    },
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,
      "io.circe"   %% "circe-generic"       % circeVersion,
      "io.circe"   %% "circe-parser"        % circeVersion,

      // test-only
      "org.scalatest" %% "scalatest"                    % "3.2.19" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0"  % Test
    )
  )
  // Step 2 of the reorganization: just the dependency wired in, common
  // is still empty. Testing whether the dependency itself is safe to
  // add, isolated from any question about what actually goes inside
  // common later.
  .dependsOn(common.jvm)

// Step 1 of the reorganization, deliberately as small as possible:
// just the empty cross-project itself, verifying it resolves in
// isolation. NOT depended on by root or consoleTyrian yet -- that's a
// separate, later step, so a problem here can't be confused with a
// problem in either of the projects that already work. No source
// files exist under common/ yet either, matching root's own JVM
// scalaVersion and consoleTyrian's JS scalaVersion respectively via
// jvmSettings/jsSettings -- the two existing projects currently use
// different Scala versions (3.3.3 vs 3.8.4), and a single shared
// cross-project needs to serve both without forcing either to change.
lazy val common = (crossProject(JSPlatform, JVMPlatform) in file("common"))
  .settings(
    name := "llmsim-common",
    // circe is itself cross-built for JVM/JS, so %%% works here the
    // same as %% does for root -- CapturedHeader's codec (step 3 of
    // the reorganization: one real type, moved and verified, before
    // moving anything else) needs it on both platforms.
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"    % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion
    )
  )
  .jvmSettings(
    scalaVersion := "3.3.3"
  )
  .jsSettings(
    scalaVersion := "3.8.4"
  )

// Deliberately minimal and separate from `root` for now -- this is the
// first Scala.js code anywhere in this project, so today's actual goal
// is just "does this compile and link", nothing more. The real
// llmsim-management-api/llmsim-console module split from
// docs/console-framework-decision.md comes later, once this compiles.
// Named console-tyrian, not console -- sbt has its own built-in
// `console` task (the REPL), and a project with that exact name would
// collide with it.
//
// scalaVersion and tyrian-io's version are pinned to what Tyrian's OWN
// shared build config for its live docs/demos actually uses (found by
// cloning github.com/PurpleKingdomGames/tyrian-docs directly and
// reading build.mill), not guessed or copied from a possibly-stale
// doc page -- Tyrian's "classic" package (the Elm-architecture API
// this console's design is built around: TyrianIOApp, Model/Msg/
// update/view/subscriptions) turned out to need a materially newer
// Scala version and a PREVIEW release of Tyrian than this project's
// existing 3.3.3 baseline, which an older, easier-to-find Tyrian
// version number would not have surfaced. Scoped to consoleTyrian
// only via a per-project override -- root's Scala version is
// completely unaffected.
lazy val consoleTyrian = (project in file("console-tyrian"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "console-tyrian",
    scalaVersion := "3.8.4",
    // No scalaJSUseMainModuleInitializer -- confirmed by reading
    // TyrianIOApp's actual source (github.com/PurpleKingdomGames/
    // tyrian): it never defines a conventional Scala.js main(). It
    // relies entirely on @JSExportTopLevel + an exported launch(...)
    // method, called explicitly from JS -- setting this to true was
    // what caused "No main module initializer was specified".
    //
    // ESModule, not the earlier NoModule default -- Vite specifically
    // needs ES modules to import Scala.js's output. Confirmed against
    // a real, complete, working reference (github.com/zetashift/
    // tyrian-vite-tailwindcss-example), not assumed.
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "io.indigoengine" %%% "tyrian-io"     % "0.30.0-M4-PREVIEW",
      // For fetching from llmsim's own API. Versions confirmed
      // together in Tyrian's own tyrian-docs http4s-dom networking
      // example (github.com/PurpleKingdomGames/tyrian-docs), not
      // guessed independently -- http4s-dom doesn't pull in
      // http4s-circe transitively, it's a separate JSON-integration
      // module.
      "org.http4s"      %%% "http4s-dom"    % "0.2.12",
      "org.http4s"      %%% "http4s-circe"  % "0.23.34"
    ),

    // Vite needs to know where Scala.js's actual output landed, and
    // that path includes the Scala version, so it's not safe to
    // hardcode in vite.config.js -- these task keys let Vite ask sbt
    // directly instead. Same pattern as the reference project above.
    fastLinkOutputDir := {
      (Compile / fastLinkJS).value
      (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value.getAbsolutePath()
    },
    fullLinkOutputDir := {
      (Compile / fullLinkJS).value
      (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value.getAbsolutePath()
    }
  )
  .dependsOn(common.js)
