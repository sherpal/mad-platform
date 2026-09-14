package be.doeraene.cli

import org.scalacheck.*
import org.scalacheck.Prop.*

object CustomGameStateParserChecks extends Properties("Game State Parser") {

  import be.doeraene.mad.game.GameActionChecks.{fiveByFiveGameStateGen, sixByFourGameStateGen}
  import CustomGameStateParser.*

  property("Round trip of game state parser works") = forAll(sixByFourGameStateGen) { gameState =>
    val generated = generate(gameState)
    val parsed    = parse(generated)
    Prop(parsed == Right(gameState)) :| s"""
                                           |$generated
                                           |
                                           |$parsed
                                           |""".stripMargin
  }

  property("Round trip of game state with 5 by 5 works") = forAll(fiveByFiveGameStateGen) { gameState =>
    val generated = generate(gameState)
    val parsed    = parse(generated)
    Prop(parsed == Right(gameState)) :| s"""
                                           |$generated
                                           |
                                           |$parsed
                                           |""".stripMargin
  }

}
