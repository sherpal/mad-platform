package be.doeraene.mad.game

import Positions.*

class PositionsSpecs extends munit.FunSuite:
  
  test("thereShouldBe24Positions") {
    assertEquals(24, allPositions.length)
  }

  test("thereShouldBe16StartingPosition") {
    assertEquals(16, Positions.startingPositions.size)
  }

  test("theKeysOfStartingPositionsShouldBeThePieces") {
    assertEquals(Positions.startingPositions.keys.toSet, GamePiece.pieces)
  }
    
  test("somePositionPlusDirectionArithmetic") {
    assertEquals(Position(0, 0), Some(topLeft))
    assertNotEquals(Position(1, 0), Option(topLeft))
    assertEquals(topLeft + right, Position(0, 1))
    assertEquals(topLeft + left, None)
  }

end PositionsSpecs
