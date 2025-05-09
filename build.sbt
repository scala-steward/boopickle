import sbt._
import Keys._
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "2.13.14"

ThisBuild / scalafmtOnCompile := scalaVersion.value.startsWith("2")

Global / onChangedBuildSource := ReloadOnSourceChanges

def addDirsFor213_+(scope: ConfigKey): Def.Initialize[Seq[File]] = Def.setting {
  (scope / unmanagedSourceDirectories).value.flatMap { dir =>
    if (dir.getPath.endsWith("scala"))
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => Nil
        case _             => file(dir.getPath ++ "-2.13+") :: Nil
      }
    else
      Nil
  }
}

val commonSettings = Seq(
  organization       := "io.suzaku",
  version            := Version.library,
  crossScalaVersions := Seq("2.12.19", "2.13.14", "3.3.6"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) =>
      Seq(
        "-Xlint",
        "-Ywarn-dead-code",
        "-Ywarn-numeric-widen",
        "-Ywarn-value-discard"
      )
    case Some((3, _)) =>
      Seq(
        "-source:3.0-migration"
      )
    case _ => throw new RuntimeException("Unknown Scala version")
  }) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => Seq("-Xfatal-warnings", "-Xfuture", "-Xlint:-unused", "-Yno-adapted-args")
    case Some((2, 13)) => Seq("-Xlint:-unused")
    case _             => Seq.empty
  }) ++ (if (scala.util.Properties.javaVersion.startsWith("1.8")) Nil else Seq("-release", "8")),
  Compile / scalacOptions ~= (_ filterNot (_ == "-Ywarn-value-discard")),
  Compile / unmanagedSourceDirectories ++= addDirsFor213_+(Compile).value,
  Test / unmanagedSourceDirectories ++= addDirsFor213_+(Test).value,
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies += "com.lihaoyi" %%% "utest" % "0.8.3" % Test,
  libraryDependencies ++= {
    if (scalaVersion.value.startsWith("2"))
      ("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided) :: Nil
    else
      Nil
  }
)

inThisBuild(
  List(
    homepage := Some(url("https://github.com/suzaku-io/boopickle")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("ochrons", "Otto Chrons", "", url("https://github.com/boopickle"))
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/suzaku-io/boopickle"),
        "scm:git:git@github.com:suzaku-io/boopickle.git",
        Some("scm:git:git@github.com:suzaku-io/boopickle.git")
      )
    ),
    Test / publishArtifact := false
  )
)

def sourceMapsToGithub: Project => Project =
  p =>
    p.settings(
      scalacOptions ++= {
        val isDotty = scalaVersion.value startsWith "3"
        val ver     = version.value
        if (isSnapshot.value)
          Nil
        else {
          val a    = p.base.toURI.toString.replaceFirst("[^/]+/?$", "")
          val g    = s"https://raw.githubusercontent.com/suzaku-io/boopickle"
          val flag = if (isDotty) "-scalajs-mapSourceURI" else "-P:scalajs:mapSourceURI"
          s"$flag:$a->$g/v$ver/" :: Nil
        }
      }
    )

lazy val preventPublicationSettings = Seq(publish / skip := true)

def onlyScala2(p: Project) = {
  def clearWhenDisabled[A](key: SettingKey[Seq[A]]) =
    Def.setting[Seq[A]] {
      val disabled = scalaVersion.value.startsWith("3")
      val as       = key.value
      if (disabled) Nil else as
    }
  p.settings(
    libraryDependencies                  := clearWhenDisabled(libraryDependencies).value,
    Compile / unmanagedSourceDirectories := clearWhenDisabled(Compile / unmanagedSourceDirectories).value,
    Test / unmanagedSourceDirectories    := clearWhenDisabled(Test / unmanagedSourceDirectories).value,
    publish / skip                       := ((publish / skip).value || scalaVersion.value.startsWith("3")),
    Test / test                          := { if (scalaVersion.value.startsWith("2")) (Test / test).value }
  )
}

lazy val boopickle = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(commonSettings)
  .settings(
    name := "boopickle"
  )
  .jsConfigure(sourceMapsToGithub)

lazy val shapeless = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(boopickle)
  .settings(commonSettings)
  .settings(
    name := "boopickle-shapeless",
    libraryDependencies ++= Seq(
      "com.chuusai" %%% "shapeless" % "2.3.12"
    )
  )
  .jsConfigure(sourceMapsToGithub)
  .configure(onlyScala2)

lazy val generateTuples = taskKey[Unit]("Generates source code for pickling tuples")

/*
Generator for all the 22 Tuple picklers. Resulting source files are written under `target`
from where they need to be copied to the `boopickle` source directory.
 */
generateTuples := {
  val picklers = (1 to 22).map { i =>
    def commaSeparated(s: Int => String, sep: String = ", ") = (1 to i).map(s).mkString(sep)

    val picklerTypes = commaSeparated(j => s"T$j: P")
    val typeTuple    = if (i == 1) s"Tuple1[T1]" else s"(${commaSeparated(j => s"T$j")})"
    val writes       = commaSeparated(j => s"write[T$j](x._$j)", "; ")
    val reads        = commaSeparated(j => s"read[T$j]")

    s"""
  implicit def Tuple${i}Pickler[$picklerTypes]: P[$typeTuple] = new P[$typeTuple] {
    override def pickle(x: $typeTuple)(implicit state: PickleState): Unit = { $writes }
    override def unpickle(implicit state: UnpickleState) = ${if (i == 1) s"Tuple1[T1]" else ""}($reads)
  }"""
  }
  IO.write(
    baseDirectory.value / "boopickle" / "shared" / "src" / "main" / "scala" / "boopickle" / "TuplePicklers.scala",
    s"""package boopickle

trait TuplePicklers extends PicklerHelper {
  ${picklers.mkString("\n")}
}
"""
  )
}

lazy val perftests = crossProject(JSPlatform, JVMPlatform)
  .settings(commonSettings)
  .settings(
    name := "perftests",
    libraryDependencies ++= Seq(
      "com.lihaoyi"       %%% "upickle"       % "3.3.1",
      "org.playframework" %%% "play-json"     % "3.0.4",
      "io.circe"          %%% "circe-core"    % "0.14.9",
      "io.circe"          %%% "circe-parser"  % "0.14.9",
      "io.circe"          %%% "circe-generic" % "0.14.9"
    ),
    preventPublicationSettings
  )
  .settings(
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) =>
          Seq(compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)))
        case _ =>
          Seq.empty
      }
    },
    Compile / scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) => Seq("-Ymacro-annotations")
        case _             => Seq.empty
      }
    }
  )
  .enablePlugins(JmhPlugin)
  .jvmSettings(
    libraryDependencies += "io.circe" %% "circe-jawn" % "0.14.9"
  )
  .jsSettings(
//    scalaJSOptimizerOptions in (Compile, fullOptJS) ~= { _.withUseClosureCompiler(false) },
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "com.lihaoyi"  %%% "scalatags"   % "0.13.1"
    )
  )
  .dependsOn(boopickle)

lazy val booPickleRoot = project
  .in(file("."))
  .settings(commonSettings)
  .settings(preventPublicationSettings)
  .aggregate(boopickle.js, boopickle.jvm, boopickle.native, shapeless.js, shapeless.jvm, shapeless.native)
