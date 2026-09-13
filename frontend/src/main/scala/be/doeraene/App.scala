package be.doeraene

import be.doeraene.mad.game.*
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import be.doeraene.components.router.*
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.gamecomponents.*
import be.doeraene.models.PlayerName
import be.doeraene.components.{moveToPath, withInitialSpecialRuleParam, Constants}
import urldsl.language.dummyErrorImpl.endOfSegments
import be.doeraene.models.GameHistory
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import be.doeraene.communication.ProgramsApi.me
import be.doeraene.components
import be.doeraene.webcomponents.ui5.{Link => _, *}
import be.doeraene.webcomponents.ui5.configkeys.IconName

object App:

  def main(args: Array[String]): Unit = {

    val _404 = div(
      "404: It seems that you got lost in MADness.",
      br(),
      "Don't worry, we are here to help. Please click ",
      Link(entry)("here", components.linkModifiers*),
      "."
    )

    def devProdPath(path: String): String =
      if scala.scalajs.LinkingInfo.developmentMode then path else "/mad-the-game/" ++ path.stripPrefix("/")

    def app2(username: String) = div(
      Bar(
        _.slots.startContent := img(src := devProdPath("/favicon.png"), widthAttr := 30),
        _.slots.startContent := Icon(
          _.name := IconName.home,
          onClick.mapTo(()) --> moveToPath(entry),
          cursor := "pointer",
          color  := "white"
        ),
        _.slots.startContent := span("Mad Platform", cursor := "pointer", onClick.mapTo(()) --> moveToPath(entry)),
        _.slots.endContent   := span(username),
        _.slots.endContent <-- {
          def choice(text: String) =
            span(Icon(_.name := IconName.`map-fill`, color := "white"), text, cursor := "default")
          Routes
            .firstOf(
              Route(entry, () => choice("Home")),
              Route(againstAI / endOfSegments, () => choice("Challenge Blue Madness")),
              Route(AINewGameView.here, () => choice("New Game")),
              Route(AILoadGameView.here, () => choice("Load Game")),
              Route.matchOnly(playAIGame, () => choice("Against Blue Madness"))
            )
            .map(_.getOrElse(span("")))
        }
      ),
      div(
        className := "Content",
        child <-- Routes
          .firstOf(
            Route(entry, () => components.home.Home(username)),
            Route(
              againstAI / endOfSegments,
              () => AIPreGameView(PlayerName.HumanPlayerName(username))
            ),
            Route(AINewGameView.here, () => AINewGameView()),
            Route(AILoadGameView.here, () => AILoadGameView()),
            Route(
              playAIGame,
              { (_: Unit, info: (Option[(GameHistory, Option[Team])], GameBoundaries.GameType, Boolean)) =>
                val gameType               = info._2
                val maybeHistoryAndTeam    = info._1
                val maybeTeam              = maybeHistoryAndTeam.flatMap(_._2)
                val withInitialSpecialRule = info._3

                val boundaries = GameBoundaries.gameBoundaryByGameType(gameType)

                val empty =
                  GameHistory.empty(GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule))
                val history = maybeHistoryAndTeam.fold(empty)(_._1)
                components.gamecomponents.AIGameView(PlayerName.HumanPlayerName(username), history, maybeTeam)
              }
            )
          )
          .map(_.getOrElse(_404))
      )
    )

    renderOnDomContentLoaded(dom.document.getElementById("root"), app2("You"))

  }
