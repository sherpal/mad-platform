package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*

/** Guards the orderings that a trained network's input and output layout are pinned to.
  *
  * Nothing in the game rules cares what order [[GamePiece.orderedPieces]] or [[GameAction.allActions]] are in, so
  * nothing else in this test suite would notice them changing - but a saved model would, silently and catastrophically:
  * its policy head would point at different moves and its input planes at different pieces, with no error anywhere.
  * These orders used to come out of `Set` and `groupBy` iteration, i.e. out of hash order, which a Scala or JDK upgrade
  * is free to change.
  *
  * If one of these fails, the question to ask is not "what is the new value" but "does a trained model exist". If one
  * does, the ordering has to be restored rather than the expectation updated.
  */
final class PinnedOrderSpecs extends munit.FunSuite:

  import GamePiece.*
  import PinnedOrderSpecs.ACTION_FINGERPRINT

  test("pieceOrderIsPinned") {
    assertEquals(
      Vector(
        blue111, blue112, blue121, blue211, blue122, blue212, blue221, blue222,
        red111, red112, red121, red211, red122, red212, red221, red222
      ),
      GamePiece.orderedPieces
    )
  }

  test("pieceOrderPairsTheTeamsUp") {
    // StateEncoder.statSlot is `index % piecesPerTeam`, which only means anything if both halves list the same stats in
    // the same order.
    assertEquals(16, GamePiece.orderedPieces.length)
    (0 until GamePiece.piecesPerTeam).foreach { slot =>
      val blue = GamePiece.orderedPieces(slot)
      val red  = GamePiece.orderedPieces(slot + GamePiece.piecesPerTeam)
      assertEquals(Team.Blue, blue.team, s"slot $slot")
      assertEquals(Team.Red, red.team, s"slot $slot")
      assertEquals(blue.statValues, red.statValues, s"slot $slot")
      assertEquals(red, GamePiece.otherTeamCounterparts(blue), s"slot $slot")
    }
  }

  test("pieceIndexIsTheInverseOfPiecesByIndex") {
    GamePiece.orderedPieces.zipWithIndex.foreach { (piece, index) =>
      assertEquals(index, GamePiece.pieceIndex(piece))
      assertEquals(piece, GamePiece.piecesByIndex(index))
    }
  }

  test("rotationPoolsAgreeWithTheirOrderedForm") {
    assertEquals(GamePiece.orderedRotationPools.map(_.toSet), GamePiece.rotationPools.toVector)
    assert(GamePiece.orderedRotationPools.forall(_.length == 3))
  }

  test("actionSpaceSizeIsPinned") {
    assertEquals(306, ActionIndex.size)
    assertEquals(153, ActionIndex.teamSize)
    assertEquals(153, GameAction.blueActions.length)
  }

  test("actionOrderIsPinned") {
    // A fingerprint rather than 306 literals. String.hashCode is specified by the JDK and matched by Scala.js, so this
    // is stable across both platforms the game compiles to.
    val fingerprint = GameAction.allActions.toVector.map(ActionIndex.descriptor).mkString("\n").hashCode
    assertEquals(
      fingerprint,
      ACTION_FINGERPRINT,
      "The order of GameAction.allActions changed. Any trained network's policy head is now wrong."
    )
  }

  test("everyBoardIsVerticallySymmetric") {
    // The precondition of the whole canonicalisation: on a board with holes that did not mirror onto themselves, the
    // mirror of a legal position could land in one.
    GameBoundaries.gameBoundaryByGameType.foreach { (gameType, boundaries) =>
      assert(boundaries.isVerticallySymmetric, s"$gameType is not vertically symmetric")
    }
  }

  test("everyStartingPositionMirrorsOntoItself") {
    GameBoundaries.gameBoundaryByGameType.foreach { (gameType, boundaries) =>
      val start    = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = false)
      val placement = NnTestSupport.placementOn(boundaries)
      assertEquals(
        placement(start),
        placement(start.mirrored),
        s"$gameType does not start from a point-reflected position"
      )
    }
  }

end PinnedOrderSpecs

object PinnedOrderSpecs:
  /** Hash of the concatenated [[ActionIndex.descriptor]]s of [[GameAction.allActions]], in order. */
  val ACTION_FINGERPRINT: Int = 386173431

