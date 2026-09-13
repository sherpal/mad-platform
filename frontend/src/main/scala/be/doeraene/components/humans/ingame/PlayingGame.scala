package be.doeraene.components.humans.ingame

import com.raquo.laminar.api.L._
import be.doeraene.communication.ProgramsApi.doesGameExists
import be.doeraene.components
import be.doeraene.components.RouteDefinitions.{entry, gameId => gameIdParam}
import be.doeraene.components.router.Link
import be.doeraene.models.PlayerName

import be.doeraene.websockets.JsonWebSocket
import be.doeraene.websocketcommunication._
import be.doeraene.mad.game.Team

import be.doeraene.components.gamecomponents.HumanGameView

import be.doeraene.globals.websocketPlayingRoom

import scala.concurrent.ExecutionContext.Implicits.global

object PlayingGame:

  import ServerToClientPlayingRoom._

  def _404(gameId: java.util.UUID) = div(
    s"The game with id $gameId does not exist. ",
    "Please return ",
    Link(entry)("here", components.linkModifiers*),
    "."
  )

  def element(playerName: String, gameId: java.util.UUID): HtmlElement = {
    val socket = JsonWebSocket[ServerToClientPlayingRoom, ClientToServerPlayingRoom, java.util.UUID](
      websocketPlayingRoom,
      gameIdParam,
      gameId
    )

    val maybeTeamSignal = socket.inEvents.collect { case ThisIsYourTeam(team) =>
      team
    }.startWithNone

    extension [T](stream: EventStream[T])
      def take(n: Long): EventStream[T] = stream
        .scanLeft((0L, Option.empty[T])) { case ((count, _), t) =>
          (count + 1L, Some(t))
        }
        .changes
        .collect {
          case (count, Some(t)) if count <= n => t
        }

    val maybeInitialConfigurationSignal = socket.inEvents
      .collect { case GameStateWrapper(allGameInfo) =>
        (
          allGameInfo.gameHistory.initialGameState,
          allGameInfo.gameHistory.actions,
          allGameInfo.redInfo.name,
          allGameInfo.blueInfo.name,
          allGameInfo.startTime
        )
      }
      .take(1)
      .startWithNone

    div(
      gameId.toString,
      child <-- maybeTeamSignal.combineWith(maybeInitialConfigurationSignal).map {
        case (Some(team), Some((initialGameState, initialActions, redPlayerName, bluePlayerName, startTime))) =>
          val opponentPlayerName = if team == Team.Blue then redPlayerName else bluePlayerName
          HumanGameView(
            PlayerName.HumanPlayerName(playerName),
            opponentPlayerName,
            team,
            initialGameState,
            initialActions,
            startTime,
            socket.outWriter,
            socket.inEvents
          )
        case _ => emptyNode
      },
      onMountCallback { ctx =>
        socket.open()(using ctx.owner)
      }
    )
  }

  def apply(playerName: String, gameId: java.util.UUID): HtmlElement = div(
    child <-- EventStream
      .fromFuture(doesGameExists(gameId))
      .map(
        if _ then element(playerName, gameId) else _404(gameId)
      )
  )

end PlayingGame
