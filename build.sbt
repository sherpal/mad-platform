import sbt.Keys.*
import sbt.projectMatrix

import java.nio.charset.StandardCharsets
import scala.sys.process.Process

import org.scalajs.linker.interface.ESVersion

val commonScalaVersion = "3.8.4"
version := "1.0.0"

val usedScalacOptions = List(
  "-encoding",
  "utf8",
  "-Werror",
  "-deprecation",
  "-unchecked",
  "-language:higherKinds",
  "-feature",
  "-language:implicitConversions"
)

val commonSettings = List(
  scalaVersion                            := commonScalaVersion,
  libraryDependencies += "org.scalameta"  %% "munit"      % "1.3.6"  % Test,
  libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.20.0" % Test,
  scalacOptions ++= usedScalacOptions
)

lazy val game = projectMatrix
  .in(file("./game"))
  .settings(
    commonSettings,
    SharedDependencies.circe
  )
  .jvmPlatform(
    scalaVersions = Seq(commonScalaVersion),
    settings = Seq(
      libraryDependencies ++= Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0")
    )
  )
  .jsPlatform(
    scalaVersions = Seq(commonScalaVersion),
    settings = Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %% "scala-java-time"      % "2.7.0",
        "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.7.0"
      ),
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
  )

lazy val `shared-logic` = projectMatrix
  .in(file("./shared-logic"))
  .settings(
    commonSettings,
    SharedDependencies.addDependencies()
  )
  .jvmPlatform(scalaVersions = Seq(commonScalaVersion))
  .jsPlatform(
    scalaVersions = Seq(commonScalaVersion),
    settings = Seq(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
  )
  .dependsOn(game)

lazy val `shared-js` = project
  .in(file("./shared-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    commonSettings,
    scalaVersion := commonScalaVersion
  )
  .dependsOn(`shared-logic`.js(commonScalaVersion))

lazy val frontend = project
  .in(file("./frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion                    := commonScalaVersion,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "development", // these are used by the magic %MODE% thing from vite
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "production",
    libraryDependencies ++= List(
      "com.raquo"   %% "laminar"            % "17.0.0",
      "be.doeraene" %% "web-components-ui5" % "1.24.0"
    ),
    commonSettings
  )
  .dependsOn(`shared-js`)

lazy val `web-worker` = project
  .in(file("./web-worker"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion                    := commonScalaVersion,
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= List("org.scala-js" %% "scalajs-dom" % "2.4.0"),
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
    },
    commonSettings
  )
  .dependsOn(`shared-js`)

def esModule = Def.settings(scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.ESModule)
})

lazy val fastOptWorker =
  taskKey[Unit]("fastOptJS the web-worker project, and copy the compiled file in Vite's assets.")
lazy val fullOptWorker =
  taskKey[Unit]("fullOptJS the web-worker project, and copy the compiled file in Vite's assets.")

def copyWorker(
    linkTask: Def.Initialize[Task[Attributed[org.scalajs.linker.interface.Report]]],
    outputTask: Def.Initialize[Task[File]]
): Def.Initialize[Task[Unit]] =
  Def.task {
    val _         = linkTask.value
    val outputDir = outputTask.value
    val targetDir = (ThisBuild / baseDirectory).value / "frontend" / "public" / "web-worker"

    println(s"Copying worker files to $targetDir (from $outputDir)")

    IO.copyDirectory(outputDir, targetDir)
  }

Global / fastOptWorker := copyWorker(
  `web-worker` / Compile / fastLinkJS,
  `web-worker` / Compile / fastLinkJSOutput
).value

Global / fullOptWorker := copyWorker(
  `web-worker` / Compile / fullLinkJS,
  `web-worker` / Compile / fullLinkJSOutput
).value

val buildFrontend = taskKey[Unit]("Build frontend")

Global / buildFrontend := Def.uncached {
  /*
  To build the frontend, we do the following things:
  - fullLinkJS the frontend sub-module
  - run npm ci in the frontend directory (might not be required)
  - package the application with vite-js (output will be in the resources of the server sub-module)
   */
  (Global / fullOptWorker).value
  (frontend / Compile / fullLinkJS).value

  val dir = (ThisBuild / baseDirectory).value

  println(s"Running npm ci in ${dir / "frontend"}")

  val npmCiExit =
    Process(Utils.npm :: "ci" :: Nil, cwd = dir / "frontend").run().exitValue()
  if (npmCiExit > 0) {
    throw new IllegalStateException(s"npm ci failed. See above for reason")
  }

  val buildExit = Process(
    Utils.npm :: "run" :: "build" :: Nil,
    cwd = dir / "frontend"
  ).run().exitValue()
  if (buildExit > 0) {
    throw new IllegalStateException(s"Building frontend failed. See above for reason")
  }

  val distFolder = dir / "frontend" / "dist"

  IO.copyFile(distFolder / "index.html", distFolder / "404.html")
}
