package be.doeraene.components

import be.doeraene.cli.CustomGameStateParser
import be.doeraene.components.router.Router
import be.doeraene.facades.jszip
import be.doeraene.facades.jszip.{GenerateOptions, JSZip}
import be.doeraene.frontendutils.{PrimaryButton, SecondaryButton}
import be.doeraene.globals.madRulesPath
import be.doeraene.mad.game.*
import be.doeraene.models.GameHistory as GameHistoryModel
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.webcomponents.ui5.configkeys.IconName
import com.raquo.laminar.api.L.*
import io.circe.syntax.*
import org.scalajs.dom
import urldsl.errors.DummyError
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*
import urldsl.vocabulary.{FromString, Printer}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JavaScriptException
import scala.util.{Failure, Success}

//noinspection TypeAnnotation

def moveToPath[T](path: PathSegment[Unit, ?]): Observer[T] =
  Observer { _ =>
    Router.router.moveTo("/" ++ path.createPath())
  }

def linkModifiers = List(
  cursor := "pointer",
  color  := Constants.mainTheme
)

def downloadRulesComponent = SecondaryButton(
  Val("Download rules"),
  Val(false),
  Observer { _ =>
    val link = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    link.href = madRulesPath
    link.innerHTML = "Download rules"
    link.target = "_blank"
    dom.document.body.appendChild(link)
    link.click()
    dom.document.body.removeChild(link)
  },
  maybeIcon = Some(IconName.download)
)

val gameHistoryParam = param[String]("history").as[GameHistoryModel](using
  new urldsl.vocabulary.Codec[String, GameHistoryModel] {
    import io.circe.parser.decode
    import io.circe.syntax.given
    def leftToRight(str: String): GameHistoryModel =
      decode[GameHistoryModel](dom.window.atob(str)).toOption.get // might blow up
    def rightToLeft(gh: GameHistoryModel): String =
      dom.window.btoa(gh.asJson.noSpaces)
  }
)

given urldsl.vocabulary.FromString[Option[Team], DummyError] = {
  case str if str == Team.Red.prettyPrint  => Right(Some(Team.Red))
  case str if str == Team.Blue.prettyPrint => Right(Some(Team.Blue))
  case "random"                            => Right(Option.empty)
  case _                                   => Left(DummyError.dummyError)
}
given urldsl.vocabulary.Printer[Option[Team]] = _.fold("random")(_.prettyPrint)

val teamParam = param[Option[Team]]("team")

given FromString[GameBoundaries.GameType, DummyError] with
  def fromString(str: String): Either[DummyError, GameBoundaries.GameType] =
    GameBoundaries.GameType.fromString(str).left.map(_ => DummyError.dummyError)

given Printer[GameBoundaries.GameType] with
  def print(gameType: GameBoundaries.GameType): String = gameType.value

val gameTypeParam = param[GameBoundaries.GameType]("game-type")

val withInitialSpecialRuleParam = param[Boolean]("initial-special-rule")

def extractGameHistory(files: jszip.Files)(using ExecutionContext): Future[GameHistoryModel] = {
  val initialGameStateFile = files.files.values
    .filter(_.name.startsWith("game-state"))
    .minBy(_.name.drop("game-state-".length).dropRight(4).toInt)

  for {
    initialGameStateEncoded <- initialGameStateFile.text
    initialGameState        <- Future.fromTry(CustomGameStateParser.parse(initialGameStateEncoded).toTry)
    actionsText             <- files.file("actions.json").text
    actions                 <- Future.fromTry(io.circe.parser.decode[List[GameAction]](actionsText).toTry)
  } yield GameHistoryModel(initialGameState, actions)
}

def downloadGameHistoryComponent(gameHistory: GameHistoryModel, errorObserver: Observer[Throwable])(using
    ExecutionContext
) = PrimaryButton(
  Val("Save game"),
  Val(false),
  Observer { _ =>
    val zip = jszip.JSZip()
    gameHistory.allGameStates.foreach { gameState =>
      zip.file(
        s"game-state-${gameState.turnNumber}.txt",
        CustomGameStateParser.generate(gameState)
      )
    }
    zip.file(
      "actions.json",
      gameHistory.actions.asJson.spaces2
    )
    zip.generate[dom.Blob].onComplete {
      case Failure(exception) =>
        dom.console.error("Zip generation failed", JavaScriptException(exception))
        errorObserver.onNext(exception)
      case Success(blob) =>
        val url = dom.URL.createObjectURL(blob)
        val a   = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
        a.href = url
        a.download = "saved-game.mad"
        dom.document.body.appendChild(a)
        a.click()
        a.remove()

        js.timers.setTimeout(0)(dom.URL.revokeObjectURL(url))
    }
  },
  maybeIcon = Some(IconName.save)
)
