package be.doeraene.components

import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState, Team}
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.webcomponents.ui5.*
import be.doeraene.frontendutils.{PrimaryButton, SecondaryButton}
import be.doeraene.models.{GameHistory as GameHistoryModel, WithTime}
import org.scalajs.dom
import be.doeraene.webcomponents.ui5.configkeys.IconName

object GameHistory:

  private val dummyMaybeActionSignal = Val(Option.empty[GameAction])

  /** Display the entire history of game states from the beginning, where all actions that have been taken are provided
    * in the [[Signal]] list.
    *
    * The list must be ordered in such a way that the n-th element in the list is the (n+1)-th taken action.
    *
    * @param team
    *   the team of the player wanting to show the game. Used to know what perspective to use.
    */
  def apply(
      boundaries: GameBoundaries,
      initialGameState: GameState,
      actionsSignal: Signal[List[WithTime[GameAction]]],
      team: Team,
      isAgainstAI: Boolean
  ): HtmlElement = div(
    marginBottom := "30px",
    Title.h3("Game History"),
    children <-- actionsSignal
      .map(_.map(_.value))
      .map(GameAction.reconstructGameStates(_, initialGameState).reverse)
      .split(_._1.turnNumber) { case (_, _, statesAndActions) =>
        displayOneGameState(
          boundaries,
          statesAndActions,
          actionsSignal.map(actions => GameHistoryModel(initialGameState, actions.toVector)),
          team,
          isAgainstAI
        )
      }
  )

  def prettyPrintAction(
      gameAction: GameAction,
      state: GameState
  ): HtmlElement = span(
    className := "pretty-print-action",
    gameAction match {
      case GameAction.Identity(actionForTeam) =>
        span(display := "flex", alignItems := "center", icon(IconName.map), "Pass")
      case action: GameAction.MovementAction =>
        val posPrint   = action.finalPositionPrint(state)
        val takenPrint = action.takenPiecePrint(state)
        span(
          display    := "flex",
          alignItems := "center",
          icon(IconName.`trend-up`),
          action.piece.prettyPrint,
          icon(IconName.`arrow-right`),
          posPrint,
          takenPrint
        )
      case action: GameAction.PieceShiftingAction =>
        action match {
          case GameAction.Permutation(piece1, piece2) =>
            span(
              display    := "flex",
              alignItems := "center",
              icon(IconName.share),
              s" ${piece1.prettyPrint} ",
              icon(IconName.synchronize),
              s" ${piece2.prettyPrint}"
            )
          case GameAction.Rotation(piece1, piece2, piece3) =>
            span(
              display    := "flex",
              alignItems := "center",
              icon(IconName.refresh),
              s" ${piece1.prettyPrint}",
              icon(IconName.redo),
              s" ${piece2.prettyPrint} ",
              icon(IconName.redo),
              s" ${piece3.prettyPrint}"
            )
        }
      case GameAction.LastRowBonus(movement1, shiftAction) =>
        span(
          display    := "flex",
          alignItems := "center",
          prettyPrintAction(movement1, state),
          span("then", paddingLeft := "5px"),
          prettyPrintAction(shiftAction, state)
        )
    }
  )

  private def displayOneGameState(
      boundaries: GameBoundaries,
      statesAndActions: Signal[(GameState, GameAction)],
      fullHistorySignal: Signal[GameHistoryModel],
      team: Team,
      isAgainstAI: Boolean
  ) = detailsTag(
    marginBottom := "5px",
    summaryTag(
      display    := "flex",
      alignItems := "center",
      cursor     := "pointer",
      child <-- statesAndActions.map { (state, action) =>
        span(display := "flex", alignItems := "center", s"[${state.turnNumber}] ", prettyPrintAction(action, state))
      }
    ),
    div(
      DisplayGameState(
        boundaries,
        statesAndActions.map(_._1),
        dummyMaybeActionSignal,
        team,
        Observer.empty,
        Observer.empty,
        Val(Option.empty)
      ),
      child <-- statesAndActions
        .map(_._1)
        .map(_.turnNumber)
        .combineWith(fullHistorySignal)
        .map { (turnNumber, history) =>
          SecondaryButton(
            Val("Rewind history to this turn"),
            Val(false),
            Observer.apply { _ =>
              val link = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
              link.href = "/" ++ playAIGame.createUrlString(
                (),
                (
                  Some((history.rewindTo(turnNumber), Some(team))),
                  history.gameType,
                  history.withInitialSpecialRule
                )
              )
              link.target = "blank"
              dom.document.body.appendChild(link)
              link.click()
              dom.document.body.removeChild(link)
            },
            maybeIcon = Some(IconName.past)
          )
        }
    )
  )

  private def icon(name: IconName): HtmlElement =
    Icon(_.name := name, marginLeft := "0.5em", marginRight := "0.5em")

end GameHistory
