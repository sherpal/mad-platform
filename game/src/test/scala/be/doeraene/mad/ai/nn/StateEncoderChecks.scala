package be.doeraene.mad.ai.nn

import org.scalacheck.*
import org.scalacheck.Prop.*

import be.doeraene.mad.game.*

object StateEncoderChecks extends Properties("State encoder checks"):

  private val anyBoundaries: Gen[GameBoundaries] =
    Gen.oneOf(GameBoundaries.gameBoundaryByGameType.values.toList)

  private val anyGameState: Gen[GameState] = anyBoundaries.flatMap(GameActionChecks.gameStateGen)

  private def planeOf(features: Array[Float], boundaries: GameBoundaries, plane: Int): Vector[Float] =
    val planeSize = boundaries.lastRow * boundaries.lastCol
    features.slice(plane * planeSize, (plane + 1) * planeSize).toVector

  property("hasTheAdvertisedLength") = forAll(anyGameState) { gameState =>
    StateEncoder.encode(gameState).length == StateEncoder.featureLength(gameState.gameBoundaries)
  }

  /** The point of canonicalising: a position and the same position seen from the other side are one input, not two. */
  property("aStateAndItsMirrorEncodeIdentically") = forAll(anyGameState) { gameState =>
    (Canonical.mirrorIsExact(gameState) ==>
      (StateEncoder.encode(gameState).toVector == StateEncoder.encode(gameState.mirrored).toVector))
  }

  property("pieceCountsLandInTheRightHalf") = forAll(anyGameState) { gameState =>
    val features  = StateEncoder.encode(gameState)
    val mover     = gameState.turnOfTeam
    val planeSize = gameState.gameBoundaries.lastRow * gameState.gameBoundaries.lastCol

    def occupied(offset: Int): Int =
      (0 until GamePiece.piecesPerTeam)
        .map(slot => planeOf(features, gameState.gameBoundaries, offset + slot).count(_ == 1f))
        .sum

    val expectedMover    = gameState.pieces.count((piece, _) => piece.team == mover)
    val expectedOpponent = gameState.pieceCount - expectedMover

    all(
      "mover" |: occupied(StateEncoder.moverPlanes) == expectedMover,
      "opponent" |: occupied(StateEncoder.opponentPlanes) == expectedOpponent,
      // One-hot across planes: no square may carry two pieces.
      "one piece per square" |: (0 until planeSize).forall { cell =>
        (0 until 2 * GamePiece.piecesPerTeam).count(plane => features(plane * planeSize + cell) == 1f) <= 1
      }
    )
  }

  property("boardMaskMatchesTheBoard") = forAll(anyGameState) { gameState =>
    val boundaries = gameState.gameBoundaries
    val mask       = planeOf(StateEncoder.encode(gameState), boundaries, StateEncoder.boardMaskPlane)

    (0 until boundaries.lastRow).forall { row =>
      (0 until boundaries.lastCol).forall { col =>
        val expected = if boundaries.isExistingPosition(row, col) then 1f else 0f
        mask(row * boundaries.lastCol + col) == expected
      }
    }
  }

  property("drawClockIsBroadcastAndNormalised") = forAll(anyGameState) { gameState =>
    val boundaries = gameState.gameBoundaries
    val clock      = planeOf(StateEncoder.encode(gameState), boundaries, StateEncoder.drawClockPlane)
    val expected   = gameState.turnsSinceLastPieceDied.toFloat / GameState.drawAfterTurnsWithoutDeath.toFloat

    clock.zipWithIndex.forall { (value, cell) =>
      val onBoard = boundaries.isExistingPosition(cell / boundaries.lastCol, cell % boundaries.lastCol)
      value == (if onBoard then expected else 0f)
    }
  }

  property("encodeIntoMatchesEncodeAtAnyOffset") = forAll(anyGameState, Gen.choose(0, 3)) { (gameState, slot) =>
    // The batching path: many positions written into one buffer. Also checks the slice is zeroed, by handing it a
    // buffer pre-filled with rubbish.
    val length = StateEncoder.featureLength(gameState.gameBoundaries)
    val batch  = Array.fill(length * 4)(7f)
    StateEncoder.encodeInto(gameState, batch, slot * length)

    batch.slice(slot * length, (slot + 1) * length).toVector == StateEncoder.encode(gameState).toVector
  }

end StateEncoderChecks
