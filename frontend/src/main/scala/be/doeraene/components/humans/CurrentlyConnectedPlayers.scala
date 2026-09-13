package be.doeraene.components.humans

import com.raquo.laminar.api.L._

object CurrentlyConnectedPlayers:

  def apply(playersStream: EventStream[List[String]]): HtmlElement = div(
    className := "CurrentlyConnectedPlayers",
    child <-- playersStream.map {
      case Nil     => label("There is no one here!")
      case players => label(s"Currently connected players: ${players.mkString(", ")}.")
    }
  )
