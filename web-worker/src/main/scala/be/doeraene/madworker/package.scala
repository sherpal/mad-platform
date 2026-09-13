package be.doeraene

import be.doeraene.mad.game._
import be.doeraene.mad.ai.minimax._
import be.doeraene.mad.ai.Player

package object madworker {

  def handleCurrentGSWithAction(
      gameState: GameState,
      action: GameAction,
      aValue: Double,
      turnAhead: Int
  ): Double = {

    def handle(state: GameState) = {
      val node = Node.MadGameStateNode(state)
      given TreeExplorer[GameState, GameAction, Team] =
        Player.jPaulDoeTheoryTreeExplorer(aValue)(state)
      node.scoreForAction(action, Node.MadGameStateNode(action(state)), state.turnOfTeam, turnAhead)
    }

    handle(gameState)

  }

}
