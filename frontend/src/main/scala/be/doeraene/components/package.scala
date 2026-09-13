package be.doeraene

import com.raquo.laminar.api.L.*
import urldsl.language.dummyErrorImpl.*
import be.doeraene.globals.madRulesPath
import be.doeraene.mad.game.GameState
import be.doeraene.cli.CustomGameStateParser.generate
import be.doeraene.frontendutils.{download, PrimaryButton}
import be.doeraene.models.GameHistory as GameHistoryModel
import urldsl.errors.DummyError
import org.scalajs.dom
import be.doeraene.communication.makeCall
import be.doeraene.components.router.Router
import be.doeraene.mad.game.*
import urldsl.language.PathSegment
import urldsl.vocabulary.FromString
import urldsl.vocabulary.Printer
import be.doeraene.webcomponents.ui5.configkeys.IconName

//noinspection TypeAnnotation
package object components {

  def moveToPath(path: PathSegment[Unit, ?]) =
    Observer { _ =>
      Router.router.moveTo("/" ++ path.createPath())
    }

  def linkModifiers = List(
    cursor := "pointer",
    color  := Constants.mainTheme
  )

  def downloadRulesComponent = PrimaryButton(
    Val("Download rules"),
    Val(false),
    Observer { _ =>
      // a(href := madRulesPath, "Download rules")
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
      import io.circe.syntax.given
      import io.circe.parser.decode
      def leftToRight(str: String): GameHistoryModel =
        decode[GameHistoryModel](dom.window.atob(str)).toOption.get // might blow up
      def rightToLeft(gh: GameHistoryModel): String =
        dom.window.btoa(gh.asJson.noSpaces)
    }
  )

  implicit val teamFromString: urldsl.vocabulary.FromString[Option[Team], DummyError] = {
    case str if str == Team.Red.prettyPrint  => Right(Some(Team.Red))
    case str if str == Team.Blue.prettyPrint => Right(Some(Team.Blue))
    case "random"                            => Right(Option.empty)
    case _                                   => Left(DummyError.dummyError)
  }
  implicit val teamPrinter: urldsl.vocabulary.Printer[Option[Team]] = _.fold("random")(_.prettyPrint)

  val teamParam = param[Option[Team]]("team")

  given FromString[GameBoundaries.GameType, DummyError] with
    def fromString(str: String): Either[DummyError, GameBoundaries.GameType] =
      GameBoundaries.GameType.fromString(str).left.map(_ => DummyError.dummyError)

  given Printer[GameBoundaries.GameType] with
    def print(gameType: GameBoundaries.GameType): String = gameType.value

  val gameTypeParam = param[GameBoundaries.GameType]("game-type")

  val withInitialSpecialRuleParam = param[Boolean]("initial-special-rule")

  val saveGamePath = (root / "api" / "saved-game.mad") ? gameHistoryParam

  def downloadGameHistoryComponent(gameHistory: GameHistoryModel) = PrimaryButton(
    Val("Save game"),
    Val(false),
    Observer { _ =>
      val link = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
      link.href = "/" ++ saveGamePath.createUrlString((), gameHistory)
      link.target = "_blank"
      link.innerHTML = "Save game"
      dom.document.body.appendChild(link)
      link.click()
      dom.document.body.removeChild(link)
    },
    maybeIcon = Some(IconName.save)
  )

}
