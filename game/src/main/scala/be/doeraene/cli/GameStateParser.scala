package be.doeraene.cli

import be.doeraene.mad.game.GameState.AnyGameState

trait GameStateParser {

  /** Describes how to read a [[java.lang.String]] as a [[GameState]]. */
  def parse(str: String): Either[Throwable, AnyGameState]

  /** Generates the [[java.lang.String]] content to be put in a file to represent this [[GameState]]
    *
    * The contract is that `parse` and `generate` must be "left-inverse" (up to types) of each other. That is,
    * {{{
    *   parse(generate(gameState)) == Right(gameState)
    * }}}
    *
    * The other side of the equation is a bit tricker because the [[java.lang.String]] could contain comments, or the
    * generate method could put some comments. But morally, they should represent the same [[GameState]].
    */
  def generate(gameState: AnyGameState): String

}
