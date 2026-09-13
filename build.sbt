import sbt.Keys.*
import sbtcrossproject.CrossPlugin.autoImport.crossProject

import java.nio.charset.StandardCharsets
import scala.sys.process.Process

val commonScalaVersion = "3.8.4"
name    := "mad"
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
  scalaVersion                             := commonScalaVersion,
  libraryDependencies += "org.scalameta"  %%% "munit"      % "0.7.26" % Test,
  libraryDependencies += "org.scalacheck" %%% "scalacheck" % "1.15.3" % Test,
  libraryDependencies += "dev.zio"        %%% "zio-test"   % "2.0.9",
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  scalacOptions ++= usedScalacOptions
)

lazy val game = crossProject(JSPlatform, JVMPlatform)
  .in(file("./game"))
  .settings(
    commonSettings,
    SharedDependencies.circe
  )
  .jvmSettings(
    libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0"
  )
  .jsSettings(
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time"      % "2.4.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.4.0",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val `shared-logic` = crossProject(JSPlatform, JVMPlatform)
  .in(file("./shared-logic"))
  .settings(
    commonSettings,
    SharedDependencies.addDependencies()
  )
  .jvmSettings(
    libraryDependencies ++= List()
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(game)

lazy val `shared-js` = project
  .in(file("./shared-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    commonSettings,
    scalaVersion := commonScalaVersion
  )
  .dependsOn(`shared-logic`.js)

lazy val frontend = project
  .in(file("./frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion                    := commonScalaVersion,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= List(
      "com.raquo"   %%% "laminar"            % "17.0.0",
      "be.doeraene" %%% "web-components-ui5" % "1.24.0"
    ),
    commonSettings,
    onLoad := {
      val outputFile   = baseDirectory.value / "scala-metadata.js"
      val frontendName = name.value

      println(s"Writing vite metadata helper at $outputFile")
      IO.writeLines(
        outputFile,
        s"""
           |const scalaVersion = "$commonScalaVersion"
           |const frontendName = "${frontendName.toLowerCase}"
           |
           |exports.scalaMetadata = {
           |  scalaVersion: scalaVersion,
           |  frontendName: frontendName,
           |}
           |""".stripMargin.split("\n").toList,
        StandardCharsets.UTF_8
      )

      onLoad.value
    }
  )
  .dependsOn(`shared-js`)

lazy val `web-worker` = project
  .in(file("./web-worker"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion                    := commonScalaVersion,
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= List("org.scala-js" %%% "scalajs-dom" % "2.4.0"),
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

Global / fastOptWorker := {
  val _         = (`web-worker` / Compile / fastLinkJS).value
  val outputDir = (`web-worker` / Compile / fastLinkJSOutput).value

  val targetDir = baseDirectory.value / "frontend" / "public" / "web-worker"

  IO.copyDirectory(
    outputDir,
    targetDir
  )
}

Global / fullOptWorker := {
  val _         = (`web-worker` / Compile / fullLinkJS).value
  val outputDir = (`web-worker` / Compile / fullLinkJSOutput).value

  val targetDir = baseDirectory.value / "frontend" / "public" / "web-worker"

  IO.copyDirectory(
    outputDir,
    targetDir
  )
}

val buildFrontend = taskKey[Unit]("Build frontend")

buildFrontend := {
  /*
  To build the frontend, we do the following things:
  - fullLinkJS the frontend sub-module
  - run npm ci in the frontend directory (might not be required)
  - package the application with vite-js (output will be in the resources of the server sub-module)
   */
  (Global / fullOptWorker).value
  (frontend / Compile / fullLinkJS).value
  val npmCiExit =
    Process(Utils.npm :: "ci" :: Nil, cwd = baseDirectory.value / "frontend").run().exitValue()
  if (npmCiExit > 0) {
    throw new IllegalStateException(s"npm ci failed. See above for reason")
  }

  val buildExit = Process(
    Utils.npm :: "run" :: "build" :: Nil,
    cwd = baseDirectory.value / "frontend"
  ).run().exitValue()
  if (buildExit > 0) {
    throw new IllegalStateException(s"Building frontend failed. See above for reason")
  }
}
