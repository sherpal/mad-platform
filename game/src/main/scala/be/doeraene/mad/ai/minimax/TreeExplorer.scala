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
        t.maybeWinner match
          case Some(winner) => if winner == turn then infinity else -infinity
          /* A game that ended without a winner is the 30-turns-without-an-exile tie-break, and it is worth exactly
           * neither a win nor a loss. Scoring it `-infinity` (which is what `maybeWinner.contains(turn)` used to
           * yield here, for *both* teams at once) told whoever asked that a drawn position was a lost one. */
          case None => 0.0
      else queenVSQueenSituation(t, turn)

    def turnOf(t: GameState): Team = t.turnOfTeam

    /** A node the search must not look past. That is [[GameState.ended]], not just "a corvette has been exiled":
      * the game also stops dead after 30 turns without an exile, and a search that ignores that keeps counting a
      * material lead several plies into positions the game will never reach - so the side that is ahead never sees
      * the draw coming and drifts into it, which is precisely the position it should be breaking open.
      */
    def isTerminalNode(t: GameState): Boolean = t.ended
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
