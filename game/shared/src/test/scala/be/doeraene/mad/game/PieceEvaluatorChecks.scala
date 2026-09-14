package be.doeraene.mad.game

import org.scalacheck.*
import org.scalacheck.Prop.*
import GameAction.*
import Positions.*
import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}

object PieceEvaluatorChecks extends Properties("Piece Evaluator") {

  val boundaries = GameBoundaries.originalSixByFour

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

  val positionGen = Gen.oneOf(boundaries.allPositions)
  val teamGen     = Gen.oneOf(List(Team.Red, Team.Blue))

  property("Odd distance is winning for the playing team") = forAll(
    positionGen,
    positionGen,
    teamGen
  ) { (bluePos, redPos, fromTeam) =>
    val gameState = queenVSQueen(bluePos, redPos, fromTeam, fromTeam)

    (bluePos.distanceTo(redPos) % 2 == 1) == (TreeExplorer.queenVSQueenSituation(gameState, fromTeam) > 0)
  }

  property("Switching point of view gives opposite score") = forAll(
    positionGen,
    positionGen,
    teamGen,
    teamGen
  ) { (bluePos, redPos, fromTeam, playingTeam) =>
    val gameState = queenVSQueen(bluePos, redPos, fromTeam, playingTeam)
    TreeExplorer.queenVSQueenSituation(gameState, Team.Red) == -TreeExplorer.queenVSQueenSituation(gameState, Team.Blue)
  }
  property("Being closer leads to bigger absolute score") = forAll(
    positionGen,
    positionGen,
    positionGen,
    teamGen,
    teamGen
  ) { (bluePos1, bluePos2, redPos, fromTeam, playingTeam) =>
    val gameState1 = queenVSQueen(bluePos1, redPos, fromTeam, playingTeam)
    val gameState2 = queenVSQueen(bluePos2, redPos, fromTeam, playingTeam)

    Prop(
      (redPos.euclideanDistanceTo(bluePos1) >= redPos.euclideanDistanceTo(bluePos2)) == (
        math.abs(TreeExplorer.queenVSQueenSituation(gameState1, Team.Red)) <= math.abs(
          TreeExplorer.queenVSQueenSituation(gameState2, Team.Red)
        )
      )
    ) :| s"${gameState1.prettyPrint}\n\n${gameState2.prettyPrint}"
  }

}
