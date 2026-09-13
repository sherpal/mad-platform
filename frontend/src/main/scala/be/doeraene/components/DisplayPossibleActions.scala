package be.doeraene.components

import be.doeraene.webcomponents.ui5.*
import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.{GameAction, GamePiece, GameState, Team}
import be.doeraene.webcomponents.ui5.configkeys.ListMode

object DisplayPossibleActions:

  def apply(
      team: Team,
      gameStateSignal: Signal[GameState],
      actionWriter: Observer[GameAction],
      displayActionWriter: Observer[Option[GameAction]],
      maybeAICompletion: Option[Signal[Int]],
      maybeHoveredPieceSignal: Signal[Option[GamePiece]]
  ): HtmlElement = div(
    minWidth <-- gameStateSignal.map(_.ended).map(if _ then "auto" else "400px"),
    children <-- gameStateSignal.map(gameState =>
      if gameState.ended then List()
      else if gameState.turnOfTeam != team then
        List(
          span(
            display    := "flex",
            alignItems := "center",
            label("Opponent is thinking...", paddingRight := "10px"),
            maybeAICompletion match {
              case Some(aiCompletion) =>
                label(
                  display    := "flex",
                  alignItems := "center",
                  ProgressIndicator(_.value <-- aiCompletion, width := "70px")
                )
              case None => span()
            }
          )
        )
      else
        List(
          div(fontWeight := "bold", "Click on one of the following actions:")
        ) ++ List(
          UList(
            _.mode      := ListMode.SingleSelect,
            List(height := "500px", overflowY := "auto"),
            className := "possible-actions",
            gameState.allValidActions.map(
              displayOneAction(_, gameState, actionWriter, displayActionWriter, maybeHoveredPieceSignal)
            )
          )
        )
    )
  )

  private def displayOneAction(
      action: GameAction,
      gameState: GameState,
      actionWriter: Observer[GameAction],
      displayActionWriter: Observer[Option[GameAction]],
      maybeHoveredPieceSignal: Signal[Option[GamePiece]]
  ) = {
    val mouseHoverBus: EventBus[Boolean] = new EventBus
    UList.item(
      GameHistory.prettyPrintAction(action, gameState),
      onMouseEnter.mapTo(Some(action)) --> displayActionWriter,
      onMouseEnter.mapTo(true) --> mouseHoverBus.writer,
      onMouseLeave.mapTo(Option.empty[GameAction]) --> displayActionWriter,
      onMouseLeave.mapTo(false) --> mouseHoverBus.writer,
      onClick.mapTo(action) --> actionWriter,
      _.selected <-- maybeHoveredPieceSignal.map(_.fold(false)(action.willPieceMove))
      // _.styles.mdcThemePrimary <-- maybeHoveredPieceSignal.map(
      //   _.fold(Constants.mainTheme)(piece =>
      //     if piece.team == Team.Blue then Constants.mainTheme else Constants.secondaryTheme
      //   )
      // )
    )
  }
