ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.27"
val circeVersion  = "0.14.9"

lazy val root = (project in file("."))
  .settings(
    name := "llmsim",
    Compile / mainClass := Some("llmsim.Main"),
    assembly / mainClass := Some("llmsim.Main"),
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
