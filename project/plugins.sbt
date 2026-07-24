addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.2.0")

// Matched to scalaJSVersion = "1.22.0" -- the exact version confirmed
// in Tyrian's own build.mill (github.com/PurpleKingdomGames/
// tyrian-docs), the version their live demos actually compile against.
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

// For the `common` cross-project (shared types between the backend and
// console-tyrian) -- reorganizing after github.com/rockthejvm/
// typelevel-rite-of-passage's common/app/server split, one small,
// separately-verifiable step at a time. Version confirmed by actually
// cloning that reference project and reading its build.sbt, not
// guessed.
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")
