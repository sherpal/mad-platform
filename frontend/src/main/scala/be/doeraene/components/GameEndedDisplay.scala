package be.doeraene.components

import be.doeraene.mad.game._
import com.raquo.laminar.api.L._
import be.doeraene.facades.confetti.confetti
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.webcomponents.ui5.*
import org.scalajs.dom

import io.circe.generic.auto._
import io.circe.syntax._
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.utils.communication.JsonTranslator

object GameEndedDisplay:
  def apply(
      playerTeam: Team,
      gameStateEvents: EventStream[GameState],
      initialGameState: GameState,
      allActionsSignal: Signal[List[GameAction]],
      isAgainstAI: Boolean
  ) = div(
    child <-- gameStateEvents
      .withCurrentValueOf(allActionsSignal)
      .filter((gs, _) => gs.ended)
      .map(displayGS(playerTeam, _, initialGameState, _, isAgainstAI))
  )

  private def displayGS(
      playerTeam: Team,
      gameState: GameState,
      initialGameState: GameState,
      actionHistory: List[GameAction],
      isAgainstAI: Boolean
  ) = div(
    gameState.maybeWinner match {
      case Some(team) if team == playerTeam => Title.h2("Game ended! You won!")
      case Some(_)                          => Title.h2("Game ended! You lost!")
      case None =>
        GameAction
          .reconstructGameStates(actionHistory, initialGameState)
          .reverse
          .map(_.swap)
          .find(_.doesSomeoneDie(_)) match {
          case Some((lastExpulsion, _)) =>
            val youWon = lastExpulsion.actionForTeam == playerTeam
            if youWon then
              div(
                Title.h2("Game ended! You won!"),
                p("Weak victory: You made the last expulsion.")
              )
            else
              div(
                Title.h2("Game ended! You lost!"),
                p("Your opponent made the last expulsion, and so obtained a semi-victory.")
              )
          case None =>
            val youWon = playerTeam == Team.Blue
            div(
              Title.h2("Game ended! You " ++ (if youWon then "won" else "lost") ++ "!"),
              p("There has been no expulsion at all, so the blues obtained a semi-victory.")
            )
        }
    },
    Option.when(isAgainstAI)(
      PrimaryButton(Val("Replay"), Val(false), Observer(_ => dom.document.location.reload()), true, None)
    ),
    onMountCallback(_ =>
      gameState.maybeWinner match {
        case Some(team) if team == playerTeam =>
          confetti.firworks()
        case _ =>
      }
    )
  )
