package be.doeraene.components

import be.doeraene.mad.game.{GameState, Team}
import be.doeraene.webcomponents.ui5.ProgressIndicator
import com.raquo.laminar.api.L.*

object OpponentIsThinking {

  /** Displays a progress bar with AI completion progression when it's its turn.
    * @param gameStateSignal
    *   signal emitting the current [[GameState]]
    * @param team
    *   [[Team]] of the player (not the AI)
    * @param maybeAICompletion
    *   signal emitting the completion percentage of thinking of AI.
    */
  def apply(gameStateSignal: Signal[GameState], team: Team, maybeAICompletion: Option[Signal[Int]]): HtmlElement = div(
    child.maybe <-- gameStateSignal.map(gameState =>
      Option.when(!gameState.ended && gameState.turnOfTeam != team) {
        span(
          display.flex,
          alignItems.center,
          label("Opponent is thinking...", paddingRight := "10px"),
          maybeAICompletion match {
            case Some(aiCompletion) =>
              label(
                display.flex,
                alignItems.center,
                ProgressIndicator(_.value <-- aiCompletion, width := "70px")
              )
            case None => span()
          }
        )
      }
    )
  )

}
