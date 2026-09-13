package be.doeraene.mad.game

import Positions._
import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}
import GamePiece._

class PieceEvaluatorSpecs extends munit.FunSuite {

  val evaluator     = PieceEvaluator.jPaulDoeFirstTheory(0.03)
  val nodeEvaluator = Node.evaluatorFromPieceEvaluator(evaluator)
  val boundaries    = GameBoundaries.originalSixByFour

  test("enemy222nextTo111LeadToDefeat") {
    val enemy222Pos = boundaries.Position(0, 0).get
    val my111Pos    = boundaries.Position(0, 1).get
    val enemy111Pos = boundaries.Position(2, 3).get
    val gameState = GameState(boundaries)(
      Map(
        blue222 -> enemy222Pos,
        red111  -> my111Pos,
        blue111 -> enemy111Pos
      ),
      5,
      2,
      false
    )
    given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(nodeEvaluator)
    assertEquals(
      -Node.infinity * 6,
      Node.MadGameStateNode(gameState).actionsAndScores(Team.Red).map(_._2).toVector.min,
      0.001
    )
  }
  def queenVSQueen(
      blue111Position: boundaries.Position,
      red111Position: boundaries.Position,
      fromTeamAngle: Team,
      nowPlaying: Team
  ): GameState =
    GameState(boundaries)(
      Map(GamePiece.blue111 -> blue111Position, GamePiece.red111 -> red111Position),
      50 + (if nowPlaying == Team.Red then 1 else 0),
      0,
      true
    )

  test("check111VS111Situation") {
    def createGameState(blueChess: String, redChess: String, fromTeamAngle: Team, nowPlaying: Team): GameState =
      (for {
        bluePos <- boundaries.Position.fromChessNotation(blueChess)
        redPos  <- boundaries.Position.fromChessNotation(redChess)
        gameState = queenVSQueen(bluePos, redPos, fromTeamAngle, nowPlaying)
      } yield gameState).get

    val nextToEachOther = createGameState("A1", "A2", Team.Blue, Team.Blue)
    assertEquals(TreeExplorer.queenVSQueenSituation(nextToEachOther, Team.Blue), TreeExplorer.infinity * 6 / 10, 0.01)
    assertEquals(TreeExplorer.queenVSQueenSituation(nextToEachOther, Team.Red), -TreeExplorer.infinity * 6 / 10, 0.01)

  }

}
