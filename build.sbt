import ReleaseTransformations._

// Constants //

val projectName = "http4s-active-requests"
val scala211    = "2.11.12"
val scala212    = "2.12.10"

// Lazy

lazy val scalaVersions = List(scala211, scala212)

// Groups //

val fs2G       = "co.fs2"
val http4sG    = "org.http4s"
val scalatestG = "org.scalatest"
val typelevelG = "org.typelevel"

// Artifacts //

val catsCoreA          = "cats-core"
val catsEffectA        = "cats-effect"
val fs2CoreA           = "fs2-core"
val http4sBlazeClientA = "http4s-blaze-client"
val http4sBlazeServerA = "http4s-blaze-server"
val http4sClientA      = "http4s-client"
val http4sDSLA         = "http4s-dsl"
val http4sServerA      = "http4s-server"
val scalatestA         = "scalatest"

// Versions //

val catsCoreV   = "1.6.1"
val catsEffectV = "1.4.0"
val fs2V        = "1.0.5"
val http4sV     = "0.20.11"
val scalatestV  = "3.0.8"

// GAVs //

lazy val catsCore          = typelevelG %% catsCoreA          % catsCoreV
lazy val catsEffect        = typelevelG %% catsEffectA        % catsEffectV
lazy val fs2Core           = fs2G       %% fs2CoreA           % fs2V
lazy val http4sBlazeClient = http4sG    %% http4sBlazeClientA % http4sV
lazy val http4sBlazeServer = http4sG    %% http4sBlazeServerA % http4sV
lazy val http4sClient      = http4sG    %% http4sClientA      % http4sV
lazy val http4sDSL         = http4sG    %% http4sDSLA         % http4sV
lazy val http4sServer      = http4sG    %% http4sServerA      % http4sV
lazy val scalatest         = scalatestG %% scalatestA         % scalatestV

// ThisBuild Scoped Settings //

ThisBuild / organization := "io.isomarcte"
ThisBuild / scalaVersion := scala212
ThisBuild / scalacOptions += "-target:jvm-1.8"
ThisBuild / javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
ThisBuild / dependencyOverrides ++= Seq(catsEffect, catsCore)

// General Configuration //
lazy val publishSettings = Seq(
  homepage := Some(
    url("https://github.com/isomarcte/http4s-active-requests")
  ),
  licenses := Seq(
    "BSD3" -> url("https://opensource.org/licenses/BSD-3-Clause")
  ),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/isomarcte/http4s-active-requests"),
      "scm:git:git@github.com:isomarcte/http4s-active-requests.git"
    )
  ),
  developers := List(
    Developer(
      "isomarcte",
      "David Strawn",
      "isomarcte@gmail.com",
      url("https://github.com/isomarcte")
    )
  ),
  credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  crossScalaVersions := scalaVersions
)

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  releaseStepCommandAndRemaining("+publishSigned"),
  commitReleaseVersion,
  tagRelease,
  setNextVersion,
  commitNextVersion,
  pushChanges
)
// Root Project //

lazy val root = (project in file("."))
  .settings(
    name := projectName,
    skip in publish := true
  )
  .aggregate(core)
  .settings(publishSettings: _*)

// Projects //

lazy val core = project
  .configs(IntegrationTest)
  .settings(
    name := s"$projectName-core",
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      fs2Core,
      http4sServer,
      scalatest         % "test,it",
      http4sBlazeServer % "it",
      http4sClient      % "it",
      http4sDSL         % "it",
      http4sBlazeClient % "it"
    ),
    addCompilerPlugin(
      "org.spire-math" % "kind-projector" % "0.9.9" cross CrossVersion.binary
    )
  )
  .settings(publishSettings: _*)
