package be.doeraene.mad.ai.nn.mcts

import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.Player.MadPlayer
import be.doeraene.mad.game.{GameAction, GameState}

/** Drives a [[SearchTree]] against a synchronous evaluator.
  *
  * This is the whole of the JVM side of running a search. A worker in the browser writes the same four
  * lines with an `await` in the middle; that is the only difference between the two platforms, and the
  * reason the tree hands out batches instead of calling a network itself.
  */
object Mcts:

  def search(state: GameState, evaluator: BatchEvaluator, config: SearchConfig): SearchTree =
    val tree = SearchTree(state, config)
    while !tree.isDone do
      val batch = tree.selectBatch()
      if batch.nonEmpty then tree.submit(evaluator.evaluate(batch))
    tree

  def bestAction(state: GameState, evaluator: BatchEvaluator, config: SearchConfig): GameAction =
    search(state, evaluator, config).bestAction

  /** A [[MadPlayer]], so a searching network drops straight into the existing tournament and benchmark
    * harness and can be scored against the minimax on the same battery of openings.
    */
  def player(evaluator: BatchEvaluator, config: SearchConfig, name: String = "MCTS"): MadPlayer =
    Player(Player.Name(s"$name-${config.simulations}"), state => bestAction(state, evaluator, config))

end Mcts
