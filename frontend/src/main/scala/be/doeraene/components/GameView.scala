package be.doeraene.components

import be.doeraene.communication.WorkerAPI.*
import be.doeraene.components.*
import be.doeraene.components.gamecomponents.PlayerFrame
import be.doeraene.mad.game.*
import be.doeraene.models.{GameHistory as GameHistoryModel, PlayerName}
import be.doeraene.webcomponents.ui5.{Bar, Button, Dialog}
import be.doeraene.workers.WorkerProtocol.CurrentGameStateWithSelectedAction
import com.raquo.laminar.api.L.*
import org.scalajs.dom

import java.time.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{span as _, *}
import scala.scalajs.js.timers.setInterval

object GameView {

  def apply(
      playerTeam: Team,
      playerChosesActionWriter: Observer[GameAction],
      allActionsSignal: Signal[List[GameAction]],
      initialGameState: GameState,
      maybeAICompletion: Option[Signal[Int]],
      playerName: PlayerName,
      opponentPlayerName: PlayerName,
      startTime: LocalDateTime,
      playerThinkingTimes: Signal[FiniteDuration],
      opponentThinkingTimes: Signal[FiniteDuration],
      modifiers: Modifier[HtmlElement]*
  ): HtmlElement = {

    val isAgainstAI: Boolean = opponentPlayerName match {
      case PlayerName.AIPlayerName => true
      case _                       => false
    }

    val displayGameActionBus: EventBus[Option[GameAction]] = new EventBus
    val gameStateSignal                                    = allActionsSignal.map(initialGameState.applyAllActions)
    val gameHasEndedSignal                                 = gameStateSignal.map(_.ended)

    val gameHistorySignal = allActionsSignal.map(actions => GameHistoryModel(initialGameState, actions))
    val playerIsPlaying   = gameStateSignal.map(_.turnOfTeam == playerTeam)

    val playerNameLabel = "player-name"

    val playNotifications: EventBus[Unit] = new EventBus

    val maybeHoveredPieceVar: Var[Option[GamePiece]]    = Var(Option.empty)
    val pieceClickEventBus: EventBus[Option[GamePiece]] = new EventBus

    val maybeSelectedPieceVar: Var[Option[GamePiece]] = Var(Option.empty)

    val saveGameErrorBus    = new EventBus[Throwable]
    val closeErrorDialogBus = new EventBus[Unit]

    div(
      padding   := "20px",
      className := "game-view",
      div(
        gameHistorySignal.changes.mapTo(Option.empty[GamePiece]) --> maybeSelectedPieceVar.writer,
        pieceClickEventBus.events.map(_.filter(_.team == playerTeam)) -->
          maybeSelectedPieceVar.updater[Option[GamePiece]] { (maybeCurrentlySelected, clickedOn) =>
            clickedOn.filterNot(maybeCurrentlySelected.contains)
          },
        display    := "flex",
        alignItems := "start",
        div(
          display        := "flex",
          justifyContent := "center",
          flexDirection  := "column",
          alignItems     := "center",
          padding        := "20px",
          PlayerFrame(
            opponentPlayerName,
            playerTeam.otherTeam,
            playerIsPlaying.map(!_),
            opponentThinkingTimes,
            gameHasEndedSignal
          ),
          DisplayGameState(
            initialGameState.gameBoundaries,
            gameStateSignal,
            displayGameActionBus.events.startWith(None),
            playerTeam,
            maybeHoveredPieceVar.writer,
            pieceClickEventBus.writer,
            maybeSelectedPieceVar.signal
          ),
          PlayerFrame(playerName, playerTeam, playerIsPlaying, playerThinkingTimes, gameHasEndedSignal)
        ),
        DisplayPossibleActions(
          playerTeam,
          gameStateSignal,
          playerChosesActionWriter,
          displayGameActionBus.writer,
          maybeAICompletion,
          maybeHoveredPieceVar.signal.combineWith(maybeSelectedPieceVar.signal).map {
            (maybeHoveredPiece, maybeSelectedPiece) => maybeSelectedPiece.orElse(maybeHoveredPiece)
          }
        ),
        GameEndedDisplay(playerTeam, gameStateSignal.changes, initialGameState, allActionsSignal, isAgainstAI),
        allActionsSignal.changes.delay(0).mapTo(None) --> displayGameActionBus.writer
      ),
      GameHistory(
        initialGameState.gameBoundaries,
        initialGameState,
        allActionsSignal,
        playerTeam,
        isAgainstAI = isAgainstAI
      ),
      be.doeraene.frontendutils.notification(playNotifications.events, "Your turn!"),
      gameStateSignal.changes.filter(_.turnOfTeam == playerTeam).mapTo(()) --> playNotifications,
      child <-- gameHistorySignal.map(history =>
        div(
          paddingBottom := "10px",
          paddingTop    := "5px",
          downloadGameHistoryComponent(history, saveGameErrorBus.writer)
        )
      ),
      br(),
      downloadRulesComponent,
      Dialog.of(
        _.showFromEvents(saveGameErrorBus.events.mapToUnit),
        _.closeFromEvents(closeErrorDialogBus.events),
        _.headerText := "Error while saving game",
        _ =>
          p(
            "Details:",
            pre(
              width.percent := 100,
              overflowX.auto,
              child.text <-- saveGameErrorBus.events.map(be.doeraene.utils.displayThrowable)
            )
          ),
        _.slots.footer := Bar.of(
          _.slots.endContent := Button.of(
            _ => "Close",
            _.events.onClick.mapToUnit --> closeErrorDialogBus.writer
          )
        )
      ),
      modifiers
    )

  }

}
