package be.doeraene.components.gamecomponents

import java.time.*

import be.doeraene.components.GameView
import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import be.doeraene.websocketcommunication.*
import be.doeraene.models.*
import be.doeraene.communication.ProgramsApi.me

import scala.scalajs.js.timers.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

object HumanGameView:

  def apply(
      playerName: PlayerName,
      opponentPlayerName: PlayerName,
      team: Team,
      initialGameState: GameState,
      initialActions: List[GameAction],
      startTime: LocalDateTime,
      outWriter: Observer[ClientToServerPlayingRoom],
      inEvents: EventStream[ServerToClientPlayingRoom]
  ): HtmlElement = {

    val allGameInfoStream = inEvents.collect { case gameStateWrapper: ServerToClientPlayingRoom.GameStateWrapper =>
      gameStateWrapper.allGameInfo
    }

    val allActionsSignal = allGameInfoStream.map(_.gameHistory.actions).startWith(initialActions)

    val redPlayerTotalTime  = allGameInfoStream.map(_.redInfo.totalThinkingTime).startWith(0.second)
    val bluePlayerTotalTime = allGameInfoStream.map(_.blueInfo.totalThinkingTime).startWith(0.second)

    val playerTotalTime   = if team == Team.Red then redPlayerTotalTime else bluePlayerTotalTime
    val opponentTotalTime = if team != Team.Red then redPlayerTotalTime else bluePlayerTotalTime

    val gameActionObserver = outWriter.contramap[GameAction](
      ClientToServerPlayingRoom.PlayAction(_)
    )

    val serverPingHandler = Var(Option.empty[SetIntervalHandle])

    GameView(
      team,
      gameActionObserver,
      allActionsSignal,
      initialGameState,
      Option.empty,
      playerName,
      opponentPlayerName,
      startTime,
      playerTotalTime,
      opponentTotalTime,
      onMountUnmountCallbackWithState(
        _ =>
          setInterval(1.minute) {
            me.foreach(println)
          },
        (_, maybeHandle) => maybeHandle.foreach(clearInterval)
      )
    )

  }

end HumanGameView
