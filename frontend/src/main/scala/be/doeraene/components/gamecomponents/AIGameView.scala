package be.doeraene.components.gamecomponents

import be.doeraene.components.GameView
import be.doeraene.mad.game.*
import be.doeraene.communication.AIApi.*
import be.doeraene.models.{AIGameOption, GameHistory as GameHistoryModel, PlayerName, WithTime}
import be.doeraene.workers.NeuralModels
import com.raquo.laminar.api.L.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

object AIGameView:

  def apply(playerName: PlayerName, gameHistory: GameHistoryModel, gameOption: AIGameOption): HtmlElement = {

    val playerTeam =
      gameOption.maybePlayerTeam.getOrElse(if scala.util.Random.nextBoolean() then Team.Red else Team.Blue)
    val initialGameState = gameHistory.initialGameState

    /* Each board needs its own network - the input is 19 x rows x cols and the heads end in a Linear
     * over rows * cols cells, so the board is baked into the weights - and only some boards have one
     * trained. Where none exists the minimax is not the fallback, it is the only player there is; at
     * this difficulty it searches to depth 4, its own strongest setting, so nothing is lost but the
     * network. NeuralModels is the single list, shared with the worker that loads the files. */
    val neuralPlaysThisBoard = NeuralModels.isTrained(initialGameState.gameType)

    val aFunction = (gameState: GameState) => gameState.pieces.size * (22 - gameState.pieces.size) / 150.0

    val aiProgress = Var(0)

    val playerChoosesNextGameActionBus: EventBus[GameAction] = new EventBus
    val aiChoosesNextGameActionBus: EventBus[GameState]      = new EventBus

    val startTime = WithTime.time.Time.now() - gameHistory.playersThinkingTimeInfo.lastUpdate

    val allActionsEvents = EventStream
      .merge(
        playerChoosesNextGameActionBus.events,
        aiChoosesNextGameActionBus.events
          .flatMapSwitch { gs =>
            EventStream.fromFuture(
              if gameOption.difficultyLevel < 4 || !neuralPlaysThisBoard then {
                askNextAction(
                  gameOption.turnAhead,
                  aFunction(gs),
                  gs,
                  progress => aiProgress.update(_ => progress)
                )
              } else {
                val sims = 800
                /* The search reports nothing until it is finished, so there is no honest progress to
                 * show - a bar creeping along would be made up. Jump to full when the move arrives. */
                aiProgress.set(0)
                (if gs.turnNumber <= 2 then
                   Future.successful(
                     Random
                       .shuffle(gs.allValidActions.filter {
                         case _: GameAction.PieceShiftingAction => true
                         case _: GameAction.Identity            => true
                         case _                                 => false
                       })
                       .head
                   )
                 else askNeuralAction(gs, sims)).andThen { case _ => aiProgress.set(100) }
              }
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
        difficulty = gameOption.difficultyLevel,
        playerThinkingTimes,
        aiThinkingTimes
      ),
      onMountBind(ctx =>
        gameStateSignal --> ((gs: GameState) =>
          if !gs.ended && gs.turnOfTeam != playerTeam then aiChoosesNextGameActionBus.writer.onNext(gs)
        )
      )
    )
  }

end AIGameView
