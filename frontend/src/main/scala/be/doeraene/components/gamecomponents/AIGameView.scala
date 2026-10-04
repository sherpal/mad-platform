package be.doeraene.components.gamecomponents

import be.doeraene.communication.AIApi.*
import be.doeraene.components.GameView
import be.doeraene.mad.game.*
import be.doeraene.models.AIGameOption.Difficulty.Type
import be.doeraene.models.{AIGameOption, GameHistory as GameHistoryModel, PlayerName, WithTime}
import com.raquo.laminar.api.L.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

object AIGameView:

  def apply(playerName: PlayerName, gameHistory: GameHistoryModel, gameOption: AIGameOption): HtmlElement = {

    val playerTeam =
      gameOption.maybePlayerTeam.getOrElse(if scala.util.Random.nextBoolean() then Team.Red else Team.Blue)
    val initialGameState = gameHistory.initialGameState

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
              gameOption.difficultyLevel.tpe match {
                case Type.Random => Future.successful(Random.shuffle(gs.allValidActions).head)
                case Type.Minimax =>
                  askNextAction(
                    gameOption.turnAhead,
                    aFunction(gs),
                    gs,
                    progress => aiProgress.update(_ => progress)
                  )
                case Type.MCTS =>
                  val sims = gameOption.sims
                  /* The search reports nothing until it is finished, so there is no honest progress to
                   * show - a bar creeping along would be made up. Jump to full when the move arrives. */
                  aiProgress.set(0)
                  askNeuralAction(gs, sims).andThen { case _ => aiProgress.set(100) }
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
        PlayerName.AIPlayerName(gameOption.difficultyLevel),
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
