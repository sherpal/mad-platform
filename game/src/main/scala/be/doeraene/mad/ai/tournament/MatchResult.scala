package be.doeraene.mad.ai.tournament

import be.doeraene.mad.ai.Player
import be.doeraene.mad.game.Team

case class MatchResult(redPlayer: Player.Name, bluePlayer: Player.Name, winner: Option[Team], numberOfTurn: Int):

  def toCSV: String = List(redPlayer.toString, bluePlayer.toString, winner.fold("")(_.toString), numberOfTurn.toString).mkString(",")

object MatchResult:

  def csvHeader: String = List("red-player", "blue-player", "winner", "number-of-turns").mkString(",")