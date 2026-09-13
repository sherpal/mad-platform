package be.doeraene.mad.ai.minimax

import be.doeraene.mad.game.{GameAction, GamePiece, GameState, Team}

trait TreeExplorer[T, Action, Turn]:
  def actions(t: T): List[Action]
  def score(t: T, turn: Turn): Double

  /** Returns the exact score. This assumes that it was known in the first place. That is, either because
    * exactSolutionIsKnown or because the game has ended.
    */
  def exactScore(t: T, turn: Turn): Double

  def isTerminalNode(t: T): Boolean
  def actionIsLikeFunction1: Function1Like[Action, T]
  def turnOf(t: T): Turn
  def prettyPrint(t: T): String

  /** Returns whether this situation is known explicitly */
  def exactSolutionIsKnown(t: T): Boolean

  /** Returns how many turn to explore in addition to the default depth of the minimax algo. Note that `tBefore` is the
    * instance of T that you receive is the instance *before* applying the action. `tAfter`, for its part, is the
    * instance of T after applying the action.
    */
  def extraTurnsForAction(action: Action, tBefore: T, tAfter: T): Int

  def actionBonus(action: Action, t: T): Double
end TreeExplorer

object TreeExplorer:

  val infinity: 1000000.0 = 1000000.0

  type MadTreeExplorer = TreeExplorer[GameState, GameAction, Team]

  /** This is a special rule for when the [[GameState]] only consists of two 111. Asks the game Author for more info
    * @param gameState
    *   current [[GameState]]
    * @param team
    *   [[Team]] from which angle we look at the game
    * @return
    *   a big number if the situation is winning, a small if not.
    */
  def queenVSQueenSituation(gameState: GameState, team: Team): Double =
    val distance          = GamePiece.blue111.manhattanDistanceToOpponent111(gameState)
    val euclideanDistance = GamePiece.blue111.euclideanDistanceToOpponent111(gameState)
    val distanceCoef      = 7.0 - euclideanDistance
    val absoluteScore     = infinity * distanceCoef / 10
    (distance % 2 == 0, gameState.turnOfTeam == team) match {
      case (true, true) => // distance is even and the player plays
        -absoluteScore
      case (false, true) => // distance is odd and the player plays
        absoluteScore
      case (true, false) => // distance is even and the player does not play
        absoluteScore
      case (false, false) => // distance is odd and the playe does not play
        -absoluteScore
    }

  final class MadGameStateTreeExplorer(
      evaluator: Node.Evaluator[GameState, Team]
  ) extends MadTreeExplorer:

    def actions(t: GameState): List[GameAction] = t.allValidActions
    def score(t: GameState, turn: Team): Double = evaluator(t, turn)
    def exactScore(t: GameState, turn: Team): Double =
      if t.ended then
        val sign = if t.maybeWinner.contains(turn) then +1 else -1
        infinity * sign
      else queenVSQueenSituation(t, turn)

    def turnOf(t: GameState): Team            = t.turnOfTeam
    def isTerminalNode(t: GameState): Boolean = t.maybeWinner.isDefined
    def actionIsLikeFunction1: Function1Like[GameAction, GameState] =
      summon[Function1Like[GameAction, GameState]]

    def exactSolutionIsKnown(t: GameState): Boolean = t.pieces.keys.forall(_.is111)

    def extraTurnsForAction(
        action: GameAction,
        tBefore: GameState,
        tAfter: GameState
    ): Int =
      if action.doesSomeoneDie(tBefore) then 0
      else
        action match {
          case movement: GameAction.MovementAction if movement.piece.piecesTakenScore(tAfter) > 0 => 1
          case _                                                                                  => 0
        }

    def prettyPrint(t: GameState): String = t.prettyPrint
    def actionBonus(action: GameAction, t: GameState): Double =
      if action.doesSomeoneDie(t) then 1.0 else 0.0
  end MadGameStateTreeExplorer

end TreeExplorer
