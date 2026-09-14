import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.Def.settings
import sbt.Keys.libraryDependencies

object SharedDependencies {

  val circeVersion = "0.14.9"

  def circe = settings(
    libraryDependencies ++= List(
      "io.circe" %%% "circe-core",
      "io.circe" %%% "circe-generic",
      "io.circe" %%% "circe-parser"
    ).map(_ % circeVersion)
  )

  def addDependencies() = settings(
    libraryDependencies ++= List(
      "be.doeraene" %%% "url-dsl" % "0.6.0",
      "dev.zio"     %%% "zio"     % "2.0.18"
    ),
    circe
  )

}
