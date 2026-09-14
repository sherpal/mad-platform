package be.doeraene.mad.game

import Positions.*
import GameAction.*
import GamePiece.*
import errors.OwnLegalityException

final class GameActionSpecs extends munit.FunSuite:

  val boundaries = GameBoundaries.originalSixByFour
  val topLeft    = boundaries.topLeft

  test("thereShouldBeNoOwnLegalityViolation") {
    assertEquals(
      List.empty[OwnLegalityException],
      GameAction.allActions.map(_.ownLegality).collect { case Left(error) => error }
    )
  }

  test("correctNumberOfPermutations") {
    assertEquals(2 * 4, GameAction.allPermutations.length)
  }

  test("correctNumberOfRotations") {
    assertEquals(2 * 4, GameAction.allRotations.length)
  }

  test("correctNumberOfPieceMoves1") {
    assertEquals(8 * 4 * 2, GameAction.oneMovements.length)
  }

  test("correctNumberOfPieceMoves2") {
    assertEquals(4 * 8 * 2, GameAction.twoMovements.length)
  }

  test("movingToTheRight") {
    val gameState     = GameState(boundaries)(Map(blue111 -> topLeft), 10, 1, true)
    val action        = GameAction.GamePieceMoves1(blue111, right)
    val nextGameState = action(gameState)

    assertEquals(11, nextGameState.turnNumber)
    assertEquals(2, nextGameState.turnsSinceLastPieceDied)
    assertEquals(
      topLeft + right,
      nextGameState.pieces.get(blue111).map(boundaries.positionFromOtherBoundaries(nextGameState.gameBoundaries))
    )
    assertEquals(topLeft + right, Some(boundaries.allPositions(1)))
  }

  test("permutationDoesNotIncreaseUnitCount") {
    val gameState     = GameState(boundaries)(Map(blue111 -> topLeft), 10, 1, true)
    val action        = GameAction.Permutation(blue111, blue222)
    val nextGameState = action(gameState)

    assert(nextGameState.pieceIsAlive(blue222))
    assert(!nextGameState.pieceIsAlive(blue111))
  }

  test("rotationDoesNotIncreaseUnitCount") {
    val gameState     = GameState(boundaries)(Map(blue112 -> topLeft), 10, 1, false)
    val action        = GameAction.Rotation(blue112, blue121, blue211)
    val nextGameState = action(gameState)

    assert(nextGameState.pieceIsAlive(blue211))
    assert(!nextGameState.pieceIsAlive(blue112))
    assert(!nextGameState.pieceIsAlive(blue121))
  }

  test("Going right on the last row enables shift bonus") {
    try {
      val gameState = GameState(boundaries)(Map(red112 -> boundaries.topLeft), 11, 1, false)

      assertEquals(
        gameState.allValidActions.count(_.isInstanceOf[LastRowBonus]),
        1
      )

      val legalBonus = gameState.allValidActions.collectFirst { case a: LastRowBonus => a }.get

      assert(legalBonus(gameState).pieceIsAlive(red221))
    } catch {
      case t: Throwable =>
        println("*********************")
        t.printStackTrace()
    }
  }

  test("Identity is not allowed on first turn without the first turn rule") {
    val gameState6x4 = GameState.initial6By4GameState(withInitialSpecialRule = false)
    assert(!GameAction.Identity(Team.Red).isLegal(gameState6x4), "Identity was not supposed to be legal!")
    assert(!GameAction.Identity(Team.Blue).isLegal(gameState6x4.allValidActions.head.act(gameState6x4)), "Identity was not supposed to be legal!")
    val gameState5x5 = GameState.initial5By5GameState(withInitialSpecialRule = false)
    assert(!GameAction.Identity(Team.Red).isLegal(gameState5x5), "Identity was not supposed to be legal!")
    assert(!GameAction.Identity(Team.Blue).isLegal(gameState5x5.allValidActions.head.act(gameState5x5)), "Identity was not supposed to be legal!")
  }

  test("Identity is allowed on first turn with the first turn rule") {
    val gameState6x4 = GameState.initial6By4GameState(withInitialSpecialRule = true)
    assert(GameAction.Identity(Team.Red).isLegal(gameState6x4), "Identity was supposed to be legal!")
    assert(GameAction.Identity(Team.Blue).isLegal(gameState6x4.allValidActions.head.act(gameState6x4)), "Identity was supposed to be legal!")
    val gameState5x5 = GameState.initial5By5GameState(withInitialSpecialRule = true)
    assert(GameAction.Identity(Team.Red).isLegal(gameState5x5), "Identity was supposed to be legal!")
    assert(GameAction.Identity(Team.Blue).isLegal(gameState5x5.allValidActions.head.act(gameState5x5)), "Identity was supposed to be legal!")
  }

  test("In an Aztec diamond game, piece 212 can go from (1,1) to (2,0) when (2,1) is empty") {
    val boundaries = GameBoundaries.aztecDiamondBoundaries
    val gameState = GameState(boundaries)(Map(
      GamePiece.blue111 -> boundaries.Position(0, 2).get,
      GamePiece.blue212 -> boundaries.Position(1, 1).get,
      GamePiece.red111 -> boundaries.Position(6, 2).get
    ), 4, 0, true)

    val legalTwoMovementActionsFor212 = GameAction.twoMovements.filter(_.piece == GamePiece.blue212).filter(_.isLegal(gameState))
    val targetPosition = boundaries.Position(2, 0).get

    assert(
      legalTwoMovementActionsFor212.exists(action => action(gameState).pieces.get(GamePiece.blue212) == Some(targetPosition)),
      s"No valid actions found to go to ${targetPosition.toChessNotation}, found legal actions were ${legalTwoMovementActionsFor212.map(_.prettyPrint(gameState)).mkString(", ")}."
    )
  }

  test("In an Aztec diamond game, piece 212 can use a bonus action if it is in (4,1), 121 is alive and (5,1) is empty") {
    val boundaries = GameBoundaries.aztecDiamondBoundaries
    val gameState = GameState(boundaries)(Map(
      GamePiece.blue111 -> boundaries.Position(0, 2).get,
      GamePiece.blue212 -> boundaries.Position(4, 1).get,
      GamePiece.blue121 -> boundaries.Position(3, 1).get,
      GamePiece.red111 -> boundaries.Position(6, 2).get
    ), 4, 0, true)

    val thereIsALegalBonusActionForBlue212 = gameState.allValidActions.exists {
      case LastRowBonus(movement1, shiftAction) if movement1.piece == GamePiece.blue212 => true
      case _ => false
    }

    assert(thereIsALegalBonusActionForBlue212)
  }


end GameActionSpecs
