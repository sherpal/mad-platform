package be.doeraene.mad.ai

import org.scalacheck._
import org.scalacheck.Prop._

import be.doeraene.mad.game._
import be.doeraene.mad.game.GameActionChecks.sixByFourGameStateGen
import be.doeraene.mad.ai.Player._
import be.doeraene.mad.ai.minimax._

object PlayerChecks extends Properties("Player Properties"):

  inline transparent def freePositionGen(
      gameState: GameState
  ): Gen[gameState.Position] =
    Gen.oneOf(gameState.gameBoundaries.allPositions.filterNot(gameState.piecesFromPosition.contains))

  val playingGameStateGen: Gen[GameState] = for {
    gameState <- sixByFourGameStateGen
    positionForRed111 <- gameState.pieces
      .get(GamePiece.red111)
      .fold(freePositionGen(gameState))(position => Gen.const(position))
    gameStateWithRed111 = gameState.copy(pieces = gameState.pieces + (GamePiece.red111 -> positionForRed111))
    positionForBlue111 <- gameStateWithRed111.pieces
      .get(GamePiece.blue111)
      .fold(freePositionGen(gameStateWithRed111))(position => Gen.const(position))
    gameStateWithBoth111 = gameStateWithRed111.copy(pieces =
      gameStateWithRed111.pieces + (GamePiece.blue111 -> positionForBlue111)
    )
  } yield gameStateWithBoth111

  property("The minimax and a 'constant' dependent minimax give the same results") = forAll(playingGameStateGen) {
    gameState =>
      val evaluator = Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(0.03))
      given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(evaluator)

      minimaxMadPlayer(1).nextAction(gameState) == gameStateDependentMinimaxMadPlayer(
        1,
        _ => summon[TreeExplorer[GameState, GameAction, Team]]
      ).nextAction(gameState)

  }

end PlayerChecks
