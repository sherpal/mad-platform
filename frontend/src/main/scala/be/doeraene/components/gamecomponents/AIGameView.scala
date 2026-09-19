package be.doeraene.components.gamecomponents

import be.doeraene.components.GameView
import be.doeraene.mad.game.*
import be.doeraene.communication.AIApi.*
import be.doeraene.models.{GameHistory as GameHistoryModel, PlayerName, WithTime}

import com.raquo.laminar.api.L.*

import scala.concurrent.ExecutionContext.Implicits.global

object AIGameView:

  def apply(playerName: PlayerName, gameHistory: GameHistoryModel, maybePlayerTeam: Option[Team]): HtmlElement = {

    val playerTeam       = maybePlayerTeam.getOrElse(if scala.util.Random.nextBoolean() then Team.Red else Team.Blue)
    val initialGameState = gameHistory.initialGameState

    val turnAhead = Var(4)

    val aFunction = (gameState: GameState) => gameState.pieces.size * (22 - gameState.pieces.size) / 150.0

    val aiProgress = Var(0)

    val playerChoosesNextGameActionBus: EventBus[GameAction] = new EventBus
    val aiChoosesNextGameActionBus: EventBus[GameState]      = new EventBus

    val startTime = WithTime.time.Time.now() - gameHistory.playersThinkingTimeInfo.lastUpdate

    val allActionsEvents = EventStream
      .merge(
        playerChoosesNextGameActionBus.events,
        aiChoosesNextGameActionBus.events
          .withCurrentValueOf(turnAhead.signal)
          .map((gs, t) => (gs, t, aFunction(gs)))
          .flatMapSwitch { (gs, t, a) =>
            EventStream.fromFuture(askNextAction(t, a, gs, progress => aiProgress.update(_ => progress)))
          }
      )
      .map(WithTime(_, startTime.until(WithTime.time.Time.now())))
      .scanLeft(gameHistory.actions)(_ :+ _)

    val gameStateSignal = allActionsEvents.map(_.map(_.value)).map(initialGameState.applyAllActions)

    val playersThinkingInfoSignal =
      allActionsEvents
        .map(GameHistoryModel(gameHistory.initialGameState, _).playersThinkingTimeInfo)

    val playerThinkingTimes = playersThinkingInfoSignal.map(_.totalForTeam(playerTeam))
    val aiThinkingTimes     = playersThinkingInfoSignal.map(_.totalForTeam(playerTeam.otherTeam))

    div(
      GameView(
        playerTeam,
        playerChoosesNextGameActionBus.writer,
        allActionsEvents.map(_.toList),
        initialGameState,
        Some(aiProgress.signal),
        playerName,
        PlayerName.AIPlayerName,
        playerThinkingTimes,
        aiThinkingTimes
      ),
      div(
        paddingTop.px    := 20,
        paddingBottom.px := 10,
        "Level (Turns ahead for the AI): ",
        select(
          controlled(
            value <-- turnAhead.signal.map(_.toString),
            onChange.mapToValue --> turnAhead.writer.contramap[String](_.toInt)
          ),
          (1 to 5).toList.map(level => option(value := level.toString, level.toString))
        )
      ),
      onMountBind(ctx =>
        gameStateSignal --> ((gs: GameState) =>
          if !gs.ended && gs.turnOfTeam != playerTeam then aiChoosesNextGameActionBus.writer.onNext(gs)
        )
      )
    )
  }

end AIGameView
