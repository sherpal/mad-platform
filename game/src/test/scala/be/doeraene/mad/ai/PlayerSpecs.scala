package be.doeraene.mad.ai

import be.doeraene.cli.CustomGameStateParser.parse
import be.doeraene.mad.game.GameAction.Permutation
import be.doeraene.mad.game.GamePiece.*

final class PlayerSpecs extends munit.FunSuite:

  test("Player should correctly sacrified itself") {
    val gameStateRepr = """
    |Turn Number: 68
    |
    |B1: Red111
    |A5: Red212
    |A6: Blue111
    |B5: Red222
    |B6: Red112
    |""".stripMargin

    val gameState = parse(gameStateRepr).toTry.get

    val player       = Player.jPaulTheoryPlayer(15, 0.03)
    val randomPlayer = Player.randomMadPlayer
    val suicide      = Permutation(blue111, blue222)
    assertEquals(
      player.nextAction(gameState),
      suicide
    )
    assertEquals(
      player.nextAction(gameState),
      suicide
    )
  }

end PlayerSpecs
