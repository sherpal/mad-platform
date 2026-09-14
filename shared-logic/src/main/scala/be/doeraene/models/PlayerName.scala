package be.doeraene.models

import io.circe.Codec

sealed trait PlayerName:
  def display: String

object PlayerName:

  case class HumanPlayerName(name: String) extends PlayerName derives Codec:
    def display: String = name
  object AIPlayerName extends PlayerName:
    def display: String = "Blue Madness"
