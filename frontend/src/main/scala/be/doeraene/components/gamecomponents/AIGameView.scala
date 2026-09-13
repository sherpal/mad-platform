package be.doeraene.components.gamecomponents

import java.time.*

import be.doeraene.components.GameView
import be.doeraene.mad.game.*
import be.doeraene.communication.AIApi.*
import be.doeraene.models.{GameHistory => GameHistoryModel, PlayerName, PlayersThinkingTimeInfo}

import scala.concurrent.duration.*
import com.raquo.laminar.api.L.*

import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

object AIGameView:

  def apply(playerName: PlayerName, gameHistory: GameHistoryModel, maybePlayerTeam: Option[Team]): HtmlElement = {

    val playerTeam       = maybePlayerTeam.getOrElse(if scala.util.Random.nextBoolean() then Team.Red else Team.Blue)
    val initialGameState = gameHistory.initialGameState

    val turnAhead = Var(4)

    val aFunction = (gameState: GameState) => gameState.pieces.size * (22 - gameState.pieces.size) / 150.0

    val aiProgress = Var(0)

    val playerChosesNextGameActionBus: EventBus[GameAction] = new EventBus
    val aiChosesNextGameActionBus: EventBus[GameState]      = new EventBus

    val startTime = LocalDateTime.now

    val allActionsEvents = EventStream
      .merge(
        playerChosesNextGameActionBus.events,
        aiChosesNextGameActionBus.events
          .withCurrentValueOf(turnAhead.signal)
          .map((gs, t) => (gs, t, aFunction(gs)))
          .flatMapSwitch { (gs, t, a) =>
            EventStream.fromFuture(askNextAction(t, a, gs, progress => aiProgress.update(_ => progress)))
          }
      )
      .scanLeft(gameHistory.actions)(_ :+ _)

    val gameStateSignal = allActionsEvents.map(initialGameState.applyAllActions)

    val playersThinkingInfoSignal =
      gameStateSignal.changes.scanLeft(PlayersThinkingTimeInfo.initial(LocalDateTime.now)) {
        case (PlayersThinkingTimeInfo(redPlayerTotal, bluePlayerTotal, lastUpdate), gameState) =>
          val now                = LocalDateTime.now
          val additionalDuration = lastUpdate.until(now, temporal.ChronoUnit.SECONDS).seconds

          println((redPlayerTotal, bluePlayerTotal, gameState.turnOfTeam, lastUpdate, now, additionalDuration))

          PlayersThinkingTimeInfo(
            redPlayerTotal + (if gameState.turnOfTeam == Team.Blue then additionalDuration else 0.second),
            bluePlayerTotal + (if gameState.turnOfTeam == Team.Red then additionalDuration else 0.second),
            now
          )
      }

    val playerThinkingTimes = playersThinkingInfoSignal.map(_.totalForTeam(playerTeam))
    val aiThinkingTimes     = playersThinkingInfoSignal.map(_.totalForTeam(playerTeam.otherTeam))

    div(
      div(
        paddingTop := "20px",
        "Level (Turns ahead for the AI): ",
        select(
          controlled(
            value <-- turnAhead.signal.map(_.toString),
            onChange.mapToValue --> turnAhead.writer.contramap[String](_.toInt)
          ),
          (1 to 5).toList.map(level => option(value := level.toString, level.toString))
        )
      ),
      GameView(
        playerTeam,
        playerChosesNextGameActionBus.writer,
        allActionsEvents,
        initialGameState,
        Some(aiProgress.signal),
        playerName,
        PlayerName.AIPlayerName,
        startTime,
        playerThinkingTimes,
        aiThinkingTimes
      ),
      onMountBind(ctx =>
        gameStateSignal --> ((gs: GameState) =>
          if !gs.ended && gs.turnOfTeam != playerTeam then aiChosesNextGameActionBus.writer.onNext(gs)
        )
      )
    )
  }

end AIGameView
