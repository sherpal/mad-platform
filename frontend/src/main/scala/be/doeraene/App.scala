package be.doeraene

import be.doeraene.mad.game.*
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import be.doeraene.components.router.*
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.gamecomponents.*
import be.doeraene.models.{AIGameOption, GameHistory, PlayerName}
import be.doeraene.components.{moveToPath, Constants}
import urldsl.language.dummyErrorImpl.endOfSegments
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import be.doeraene.components
import be.doeraene.webcomponents.ui5.{Link as _, *}
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
        className := "app-bar",
        _.slots.startContent := img(
          className := "app-bar-favicon",
          src       := devProdPath("/favicon.png"),
          widthAttr := 30
        ),
        _.slots.startContent := Icon(
          _.name := IconName.home,
          onClick.mapTo(()) --> moveToPath(entry),
          cursor := "pointer",
          color  := "white"
        ),
        _.slots.startContent := span(
          className := "app-bar-brand",
          "Mad Platform",
          cursor := "pointer",
          onClick.mapTo(()) --> moveToPath(entry)
        ),
        _.slots.endContent := span(className := "app-bar-username", username),
        _.slots.endContent <-- {
          def choice(text: String) =
            span(
              className := "app-bar-route",
              Icon(_.name := IconName.`map-fill`, color := "white"),
              text,
              cursor := "default"
            )
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
              { (_: Unit, info: (Option[GameHistory], GameBoundaries.GameType, AIGameOption)) =>
                val maybeHistory = info._1
                val gameType     = info._2
                val gameOptions  = info._3

                val boundaries = GameBoundaries.gameBoundaryByGameType(gameType)

                val empty =
                  GameHistory.empty(
                    GameState.initialGameStateWithBoundaries(boundaries, gameOptions.withInitialSpecialRule)
                  )
                val history = maybeHistory.getOrElse(empty)
                components.gamecomponents.AIGameView(PlayerName.HumanPlayerName(username), history, gameOptions)
              }
            )
          )
          .map(_.getOrElse(_404))
      )
    )

    renderOnDomContentLoaded(dom.document.getElementById("root"), app2("You"))

  }
