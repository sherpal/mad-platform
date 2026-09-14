package be.doeraene.mad.game

import be.doeraene.mad.game.GameBoundaries.DefaultFourBySix

final class GameBoundariesSpecs extends munit.FunSuite {

  test("Starting position for original 6 by 4 is 16 length long") {
    assertEquals(GameBoundaries.originalSixByFour.startingPositions.size, 16)
  }

  test("Starting position for the first 5 by 5 is 16 length long") {
    assertEquals(GameBoundaries.defaultFiveByFive.startingPositions.size, 16)
  }

  test("All starting positions must be 16 length long") {
    GameBoundaries.gameBoundaryByGameType.foreach { (gameType, boundaries) =>
      assertEquals(
        boundaries.startingPositions.values.toSet.size,
        16,
        s"The number of positions did not match for $gameType."
      )
    }
  }

  test("In 6x4 and 5x5, the bonus positions are the last row") {
    for {
      boundaries <- List(GameBoundaries.OriginalSixByFour(), GameBoundaries.DefaultFiveByFive())
      team       <- List(Team.Red, Team.Blue)
    } {
      val bonusPositions: Set[boundaries.Position] = boundaries.bonusActionPositions(team)
      assertEquals(
        bonusPositions,
        boundaries.allPositions.toSet.filter(_.row == team.lastRow(boundaries.lastRow)),
        s"Positions did not match for $team and ${boundaries.gameType}"
      )
    }
  }

  test("Position (1,1) is a bonus position for team Red in the Aztec diamond") {
    val boundaries   = GameBoundaries.AztecDiamond()
    val testPosition = boundaries.Position(1, 1).get
    assert(
      boundaries.bonusActionPositions(Team.Red).contains(testPosition),
      s"Bonus positions for Red in Aztec diamond were ${boundaries.bonusActionPositions(Team.Red).mkString(", ")} and did not contain $testPosition."
    )
  }

  test("Position (2,1) is not a bonus position for team Red in the Aztec diamond") {
    val boundaries   = GameBoundaries.AztecDiamond()
    val testPosition = boundaries.Position(2, 1).get
    assert(
      !boundaries.bonusActionPositions(Team.Red).contains(testPosition),
      s"Bonus positions for Red in Aztec diamond where ${boundaries.bonusActionPositions(Team.Red).mkString(", ")} and did contain $testPosition."
    )
  }

  test("Position (1,1) + right + top is a valid position") {
    val boundaries      = GameBoundaries.AztecDiamond()
    val initialPosition = boundaries.Position(1, 1).get
    assertEquals(initialPosition + Positions.right, Some(boundaries.Position(1, 2).get))
    assertEquals((initialPosition + Positions.right).flatMap(_ + Positions.top), Some(boundaries.Position(0, 2).get))
  }

  test("Position (1, 0) is a bonus position for 4x6") {
    val boundaries   = DefaultFourBySix()
    val testPosition = boundaries.Position(1, 0).get
    assert(
      boundaries.bonusActionPositions(Team.Red).contains(testPosition),
      s"Bonus positions for Red in 4x6 were ${boundaries.bonusActionPositions(Team.Red).mkString(", ")} and did not contain $testPosition."
    )
  }

  test("Position (0, 2) is a bonus position for 4x6") {
    val boundaries   = DefaultFourBySix()
    val testPosition = boundaries.Position(0, 2).get
    assert(
      boundaries.bonusActionPositions(Team.Red).contains(testPosition),
      s"Bonus positions for Red in 4x6 were ${boundaries.bonusActionPositions(Team.Red).mkString(", ")} and did not contain $testPosition."
    )
  }

  test("Position (1, 2) is not a bonus position for 4x6") {
    val boundaries   = DefaultFourBySix()
    val testPosition = boundaries.Position(1, 2).get
    assert(
      !boundaries.bonusActionPositions(Team.Red).contains(testPosition),
      s"Bonus positions for Red in 4x6 were ${boundaries.bonusActionPositions(Team.Red).mkString(", ")} and did contain $testPosition."
    )
  }

}
