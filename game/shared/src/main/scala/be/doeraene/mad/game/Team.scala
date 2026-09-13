package be.doeraene.mad.game

import Ranges.<

trait Team:
  def value: Team.TeamIndex
  def otherTeam: Team
  def firstRow(numRows: Int): Int
  final def lastRow(numRows: Int): Int = otherTeam.firstRow(numRows)
  def _111: GamePiece
  final def prettyPrint: String = "Team" ++ toString

object Team:
  type TeamIndex = 0 | 1

  case object Blue extends Team:
    def value                                        = 0
    def otherTeam                                    = Red
    def firstRow(numRows: Int): Int = 0
    def _111: GamePiece                              = GamePiece.blue111
  case object Red extends Team:
    def value                                        = 1
    def otherTeam                                    = Blue
    def firstRow(numRows: Int): Int = numRows - 1
    def _111: GamePiece                              = GamePiece.red111
end Team
