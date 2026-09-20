package be.doeraene.components

import be.doeraene.components.*
import be.doeraene.components.gamecomponents.PlayerFrame
import be.doeraene.mad.game.*
import be.doeraene.models.{GameHistory as GameHistoryModel, PlayerName, WithTime}
import be.doeraene.webcomponents.ui5.{Bar, Button, Dialog}
import com.raquo.laminar.api.L.*

import java.time.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{span as _, *}

object GameView {

  def apply(
      playerTeam: Team,
      playerChosesActionWriter: Observer[GameAction],
      allActionsSignal: Signal[List[WithTime[GameAction]]],
      initialGameState: GameState,
      maybeAICompletion: Option[Signal[Int]],
      playerName: PlayerName,
      opponentPlayerName: PlayerName,
      playerThinkingTimes: Signal[WithTime.time.Time],
      opponentThinkingTimes: Signal[WithTime.time.Time],
      modifiers: Modifier[HtmlElement]*
  ): HtmlElement = {

    val isAgainstAI: Boolean = opponentPlayerName match {
      case PlayerName.AIPlayerName => true
      case _                       => false
    }

    val displayGameActionBus: EventBus[Option[GameAction]] = new EventBus
    val gameStateSignal    = allActionsSignal.map(_.map(_.value)).map(initialGameState.applyAllActions)
    val gameHasEndedSignal = gameStateSignal.map(_.ended)

    val gameHistorySignal = allActionsSignal.map(actions => GameHistoryModel(initialGameState, actions.toVector))
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
        className := "game-main-row",
        display.flex,
        alignItems.start,
        div(
          className := "game-board-column",
          display.flex,
          justifyContent.center,
          flexDirection.column,
          alignItems.center,
          padding.px := 20,
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
            maybeSelectedPieceVar.signal,
            playerChosesActionWriter
          ),
          PlayerFrame(playerName, playerTeam, playerIsPlaying, playerThinkingTimes, gameHasEndedSignal),
          OpponentIsThinking(
            gameStateSignal,
            playerTeam,
            maybeAICompletion
          )
        ),
        GameEndedDisplay(
          playerTeam,
          gameStateSignal.changes,
          initialGameState,
          allActionsSignal.map(_.map(_.value)),
          isAgainstAI
        ),
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
