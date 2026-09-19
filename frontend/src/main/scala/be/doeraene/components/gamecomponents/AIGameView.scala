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

    /* Defaults to the network because it is simply the stronger player now: searching over it beats the
     * minimax at depth 4, which is the minimax's own best setting. The old engine stays selectable -
     * it is what every previous game was played against, and it is the only one that works if the model
     * has not been fetched. */
    val useNeural   = Var(true)
    val simulations = Var(800)

    val playerChoosesNextGameActionBus: EventBus[GameAction] = new EventBus
    val aiChoosesNextGameActionBus: EventBus[GameState]      = new EventBus

    val startTime = WithTime.time.Time.now() - gameHistory.playersThinkingTimeInfo.lastUpdate

    val allActionsEvents = EventStream
      .merge(
        playerChoosesNextGameActionBus.events,
        aiChoosesNextGameActionBus.events
          .withCurrentValueOf(turnAhead.signal, useNeural.signal, simulations.signal)
          .flatMapSwitch { (gs, t, neural, sims) =>
            EventStream.fromFuture(
              if neural then
                /* The search reports nothing until it is finished, so there is no honest progress to
                 * show - a bar creeping along would be made up. Jump to full when the move arrives. */
                aiProgress.set(0)
                askNeuralAction(gs, sims).andThen { case _ => aiProgress.set(100) }
              else askNextAction(t, aFunction(gs), gs, progress => aiProgress.update(_ => progress))
            )
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
        "Engine: ",
        select(
          controlled(
            value <-- useNeural.signal.map(if _ then "neural" else "minimax"),
            onChange.mapToValue --> useNeural.writer.contramap[String](_ == "neural")
          ),
          option(value := "neural", "Neural network"),
          option(value := "minimax", "Minimax")
        )
      ),
      div(
        paddingBottom.px := 10,
        child <-- useNeural.signal.map { neural =>
          if neural then
            div(
              "Strength (simulations per move): ",
              select(
                controlled(
                  value <-- simulations.signal.map(_.toString),
                  onChange.mapToValue --> simulations.writer.contramap[String](_.toInt)
                ),
                List(200, 400, 800, 1600, 3200).map(count => option(value := count.toString, count.toString))
              )
            )
          else
            div(
              "Level (Turns ahead for the AI): ",
              select(
                controlled(
                  value <-- turnAhead.signal.map(_.toString),
                  onChange.mapToValue --> turnAhead.writer.contramap[String](_.toInt)
                ),
                (1 to 5).toList.map(level => option(value := level.toString, level.toString))
              )
            )
        }
      ),
      onMountBind(ctx =>
        gameStateSignal --> ((gs: GameState) =>
          if !gs.ended && gs.turnOfTeam != playerTeam then aiChoosesNextGameActionBus.writer.onNext(gs)
        )
      )
    )
  }

end AIGameView
