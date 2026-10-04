package be.doeraene.models

import be.doeraene.models.AIGameOption.Difficulty
import io.circe.Codec

sealed trait PlayerName:
  def display: String

object PlayerName:

  case class HumanPlayerName(name: String) extends PlayerName derives Codec:
    def display: String = name
  case class AIPlayerName(difficulty: Difficulty) extends PlayerName:
    def display: String = s"Blue Madness (${difficulty.name})"
