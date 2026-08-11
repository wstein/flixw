val scala2Version = "2.13.18"

lazy val root = project
  .in(file("."))
  .settings(
    name := "flixw",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala2Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test
  )
