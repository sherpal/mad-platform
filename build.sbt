import sbt.Keys.*
import sbt.projectMatrix

import java.nio.charset.StandardCharsets
import scala.sys.process.Process

import org.scalajs.linker.interface.{ESVersion, OutputPatterns}
import org.scalajs.jsenv.nodejs.NodeJSEnv

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
      libraryDependencies ++= Seq(
        "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
        // Runs the network exported by python/export_onnx.py. The browser loads that same file through
        // onnxruntime-web, so there is one model and one implementation of it, not two.
        "com.microsoft.onnxruntime" % "onnxruntime" % "1.20.0"
      )
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
        // Node (used by `test`/`run`) only recognizes a .js file as an ES module if a
        // package.json declares "type": "module", which we don't have next to the linker
        // output. Naming the file .mjs makes Node treat it as ESM unconditionally; browsers
        // don't care about the extension either way.
        .withOutputPatterns(OutputPatterns.fromJSFile("%s.mjs"))
    },
    // `run`/`test` execute the linked output with Node.js. Node's V8 still gates the Wasm
    // exception-handling feature (exnref) that the WebAssembly backend's runtime relies on
    // behind this flag on versions before it's unconditionally enabled; requires Node.js 22+.
    jsEnv := Def.uncached(new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--experimental-wasm-exnref")))),
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
    val root      = (ThisBuild / baseDirectory).value
    val targetDir = root / "frontend" / "public" / "web-worker"

    println(s"Copying worker files to $targetDir (from $outputDir)")

    IO.copyDirectory(outputDir, targetDir)
    copyOnnxRuntime(root)
  }

/** Puts onnxruntime-web next to the worker that imports it.
  *
  * It has to sit *there* specifically. The worker is copied into `public/`, which Vite serves
  * untouched, so nothing resolves bare module specifiers for it the way it would for the frontend -
  * `import "onnxruntime-web/wasm"` reaches the browser verbatim and fails to load the whole worker.
  * The worker therefore imports `./ort/ort.wasm.bundle.min.mjs`, a relative path a browser can resolve
  * on its own, and this puts the file at that path.
  *
  * Copied from node_modules rather than committed: 14MB, reproducible from `npm ci`, and pinned by the
  * lockfile. Only the plain CPU build is taken - the jsep (WebGPU), jspi and asyncify variants are
  * another 69MB between them and nothing here asks for any of them. The model itself *is* committed,
  * under `frontend/public/nn`, since nothing can regenerate it.
  */
def copyOnnxRuntime(root: File): Unit = {
  val source = root / "frontend" / "node_modules" / "onnxruntime-web" / "dist"
  val target = root / "frontend" / "public" / "web-worker" / "ort"

  if (!source.exists) {
    println(s"[warn] $source is missing - run npm ci in frontend/, or the neural engine will not load")
  } else {
    IO.createDirectory(target)
    val wanted = List(
      "ort.wasm.bundle.min.mjs",         // the runtime itself, as one self-contained ES module
      "ort-wasm-simd-threaded.wasm",     // the binary it fetches at init, via env.wasm.wasmPaths
      "ort-wasm-simd-threaded.mjs"
    )
    val copied = wanted.flatMap { name =>
      val file = source / name
      if (file.exists) { IO.copyFile(file, target / name); Some(name) }
      else { println(s"[warn] onnxruntime-web did not ship $name"); None }
    }
    println(s"Copied ${copied.size} onnxruntime-web files to $target")
  }
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
