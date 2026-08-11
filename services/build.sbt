// Reserved build definition for a possible flixw-services.jar. Not built today, and not
// part of any release. See README.md in this directory for the entry condition.
lazy val root = project
  .in(file("."))
  .settings(
    name := "flixw-services",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test
  )
