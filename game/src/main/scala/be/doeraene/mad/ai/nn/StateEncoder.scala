package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*

/** Turns a [[GameState]] into the stack of feature planes a convolutional network takes as input.
  *
  * The state is canonicalised first (see [[Canonical]]), so plane `p` always means the same thing to the player to
  * move, whichever team that is. Layout is plane-major, `plane * rows * cols + row * cols + col`, which is the NCHW
  * order ONNX Runtime expects.
  *
  * The encoding is complete: `turnNumber` beyond whose turn it is carries no information the rules look at, so nothing
  * but the piece placement, the draw clock and the opening-rule flag is needed for the position to be Markovian. That
  * is why there are no history planes here, unlike in chess where repetition rules force them.
  *
  * Plane counts are fixed but board dimensions are not, so a model is tied to one [[GameBoundaries]]; [[featureLength]]
  * is the input size for a given board.
  */
object StateEncoder:

  /** Planes 0 until 8: pieces of the player to move, one per stat combination, in [[GamePiece.orderedPieces]] order. */
  val moverPlanes: Int = 0

  /** Planes 8 until 16: the opponent's pieces, same order. */
  val opponentPlanes: Int = GamePiece.piecesPerTeam

  /** How close the position is to the no-capture draw, in `0f` until `1f`, broadcast over the board. */
  val drawClockPlane: Int = 2 * GamePiece.piecesPerTeam

  /** Which squares exist. Constant for a given board, but convolutions have no other way to tell a hole from an empty
    * square, and the two boards with holes need it.
    */
  val boardMaskPlane: Int = drawClockPlane + 1

  /** Whether the player to move is still under the opening rule, and so may only permute, rotate or pass. Only ever set
    * on the first two plies, but without it those positions are indistinguishable from ordinary ones.
    */
  val openingRulePlane: Int = boardMaskPlane + 1

  val planeCount: Int = openingRulePlane + 1

  /** Number of floats [[encode]] produces for this board. */
  def featureLength(boundaries: GameBoundaries): Int = planeCount * boundaries.lastRow * boundaries.lastCol

  /** Where a piece sits among the 8 stat combinations, regardless of team. Relies on [[GamePiece.orderedPieces]]
    * listing both teams in the same stat order.
    */
  private inline def statSlot(piece: GamePiece): Int = GamePiece.pieceIndex(piece) % GamePiece.piecesPerTeam

  def encode(gameState: GameState): Array[Float] =
    val features = new Array[Float](featureLength(gameState.gameBoundaries))
    encodeInto(gameState, features, 0)
    features

  /** Writes the planes into `features` starting at `offset`, so a batch can be assembled in one buffer without an
    * intermediate array per position. The slice is zeroed first, so a buffer may be reused across calls.
    */
  def encodeInto(gameState: GameState, features: Array[Float], offset: Int): Unit =
    val state = Canonical.canonical(gameState)
    // Singleton type, so that the positions `boundaries.rows` hands out are the ones `state.piecesFromPosition` is
    // keyed by; widening to plain `GameBoundaries` makes them two unrelated path-dependent types.
    val boundaries: state.gameBoundaries.type = state.gameBoundaries
    val numRows                               = boundaries.lastRow
    val numCols    = boundaries.lastCol
    val planeSize  = numRows * numCols

    java.util.Arrays.fill(features, offset, offset + planeCount * planeSize, 0f)

    val occupants   = state.piecesFromPosition
    val drawClock   = state.turnsSinceLastPieceDied.toFloat / GameState.drawAfterTurnsWithoutDeath.toFloat
    val underRule   = state.withInitialSpecialRule && !state.teamAlreadyPlayed(state.turnOfTeam)
    val openingFlag = if underRule then 1f else 0f

    var row = 0
    while row < numRows do
      val rowPositions = boundaries.rows(row)
      var col = 0
      while col < numCols do
        rowPositions(col) match
          case Some(position) =>
            val cell = offset + row * numCols + col
            features(cell + boardMaskPlane * planeSize) = 1f
            features(cell + drawClockPlane * planeSize) = drawClock
            features(cell + openingRulePlane * planeSize) = openingFlag
            occupants.get(position) match
              case Some(piece) =>
                // The state is canonical, so red is always the player to move.
                val planeGroup = if piece.team == Team.Red then moverPlanes else opponentPlanes
                features(cell + (planeGroup + statSlot(piece)) * planeSize) = 1f
              case None => ()
          case None => ()
        col += 1
      row += 1

end StateEncoder
