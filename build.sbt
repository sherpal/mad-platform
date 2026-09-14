import sbt.Keys.*
import sbt.projectMatrix

import java.nio.charset.StandardCharsets
import scala.sys.process.Process

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
  libraryDependencies += "org.scalameta"  %% "munit"      % "0.7.26" % Test,
  libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.15.3" % Test,
  libraryDependencies += "dev.zio"        %% "zio-test"   % "2.0.9",
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
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
        "io.github.cquiroz" %% "scala-java-time"      % "2.4.0",
        "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.4.0"
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
    libraryDependencies ++= List(
      "com.raquo"   %% "laminar"            % "17.0.0",
      "be.doeraene" %% "web-components-ui5" % "1.24.0"
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
    libraryDependencies ++= List("org.scala-js" %% "scalajs-dom" % "2.4.0"),
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

Global / fastOptWorker := Def.uncached {
  val _         = (`web-worker` / Compile / fastLinkJS).value
  val outputDir = (`web-worker` / Compile / fastLinkJSOutput).value

  val targetDir = baseDirectory.value / "frontend" / "public" / "web-worker"

  IO.copyDirectory(
    outputDir,
    targetDir
  )
}

Global / fullOptWorker := Def.uncached {
  val _         = (`web-worker` / Compile / fullLinkJS).value
  val outputDir = (`web-worker` / Compile / fullLinkJSOutput).value

  val targetDir = baseDirectory.value / "frontend" / "public" / "web-worker"

  IO.copyDirectory(
    outputDir,
    targetDir
  )
}

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
