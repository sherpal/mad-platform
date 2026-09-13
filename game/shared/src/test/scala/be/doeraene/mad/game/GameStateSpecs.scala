package be.doeraene.mad.game

import Positions._
import GamePiece._

class GameStateSpecs extends munit.FunSuite {

  def applyStateTestsFor(
      boundaries: GameBoundaries
  ): Unit = {
    val gameType = boundaries.gameType

    test(s"$gameType gameStateWithOnlyBlue111ShouldMakeBlueWinner") {
      val gameState = GameState(boundaries)(Map(blue111 -> boundaries.topLeft), 10, 0, false)

      assert(gameState.ended)
      assertEquals(Option[Team](Team.Blue), gameState.maybeWinner)
    }
  }

  applyStateTestsFor(GameBoundaries.originalSixByFour)
  applyStateTestsFor(GameBoundaries.defaultFiveByFive)

}
