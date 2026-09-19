package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*

object NnTestSupport:

  /** Piece placement of `gameState`, rebased onto `target`'s `Position` type.
    *
    * Two [[GameState]]s have unrelated `Position` types even when they share the same [[GameBoundaries]] value, because
    * `Position` is path-dependent on the state. Comparing the placements of a state and its mirror therefore needs them
    * put back on a common path first.
    */
  def placementOn(target: GameBoundaries)(gameState: GameState): Map[GamePiece, target.Position] =
    gameState.pieces.view.mapValues(target.positionFromOtherBoundaries(gameState.gameBoundaries)).toMap

  /** Whether two states describe the same position, ignoring [[GameState.turnNumber]].
    *
    * [[GameState.mirrored]] shifts the turn number by one on purpose (see its scaladoc), so the commuting law it
    * satisfies is about the pieces, the draw clock and whose turn it is - not about the ply count.
    */
  def samePositionIgnoringTurnNumber(left: GameState, right: GameState): Boolean =
    left.gameBoundaries == right.gameBoundaries &&
      left.turnOfTeam == right.turnOfTeam &&
      left.turnsSinceLastPieceDied == right.turnsSinceLastPieceDied &&
      placementOn(left.gameBoundaries)(left) == placementOn(left.gameBoundaries)(right)

end NnTestSupport
