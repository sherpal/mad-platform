package be.doeraene.mad.ai

import be.doeraene.mad.ai.Player.MadPlayer
import be.doeraene.mad.game.GameState

package object tournament {

  def playMadTournament(
      players: List[MadPlayer],
      initialGameState: GameState
  ): List[MatchResult] = {
    val actualPlayers = players.distinctBy(_.name)

    println(s"Tournament started with the following players: ${actualPlayers.map(_.name).mkString(", ")}")
    val totalGames = actualPlayers.length * (actualPlayers.length - 1)
    println(s"There will be $totalGames games in total.")

    var progress = 0

    for {
      redPlayer  <- actualPlayers
      bluePlayer <- actualPlayers
      _               = println(s"${redPlayer.name} against ${bluePlayer.name}")
      matchHistory    = Player.playMadGame(redPlayer, bluePlayer, initialGameState, verbose = false)
      endingGameState = matchHistory.last
      maybeWinner     = endingGameState.maybeWinner
      _               = println(s"Game ended in ${endingGameState.turnNumber} turns")
      _ = println(maybeWinner match {
        case Some(team) => s"Winner is $team"
        case None       => "It's a tie!"
      })
      result = MatchResult(redPlayer.name, bluePlayer.name, winner = maybeWinner, endingGameState.turnNumber)
      _ = {
        progress += 1
      }
      _ = println(s"Progress: ${progress * 100.0 / totalGames}%")
    } yield result
  }

}
