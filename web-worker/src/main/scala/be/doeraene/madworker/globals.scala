package be.doeraene.madworker

import be.doeraene.mad.game.*
import be.doeraene.mad.ai.minimax.*
import be.doeraene.mad.ai.Player

def handleCurrentGSWithAction(
    gameState: GameState,
    action: GameAction,
    aValue: Double,
    turnAhead: Int
): Double = {

  def handle(state: GameState) = {
    val node = Node.MadGameStateNode(state)
    given TreeExplorer[GameState, GameAction, Team] =
      // Player.jPaulDoeTheoryTreeExplorer(aValue)(state)
      Player.tacticalTreeExplorer
    node.scoreForAction(action, Node.MadGameStateNode(action(state)), state.turnOfTeam, turnAhead)
  }

  println(s"Using tactical tree explorer")

  handle(gameState)

}
