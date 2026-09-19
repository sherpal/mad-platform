package be.doeraene.mad.ai.nn.mcts

import be.doeraene.mad.game.GameState
import be.doeraene.perf.NatArray

/** What a network says about one position, from the point of view of the side to move.
  *
  * @param policyLogits
  *   raw, unmasked logits over [[be.doeraene.mad.ai.nn.ActionIndex.teamSize]]. Unmasked on purpose: the
  *   network is never trained to push illegal moves down, so masking has to happen where the legal moves
  *   are known, which is here rather than in the model. [[SearchTree]] does it before the softmax.
  * @param value
  *   how good the position is for the side to move, in -1 to 1.
  */
final case class Evaluation(policyLogits: Array[Float], value: Float)

/** Evaluates a batch of positions in one go.
  *
  * Batching is not an optimisation here, it is the point: a single 6x4 position is ~15 MFLOP, far too
  * little to keep any backend busy, and a search that evaluated one leaf at a time would spend all its
  * wall-clock in call overhead.
  *
  * Synchronous, so this is the JVM and testing interface. The browser's runtime only offers a
  * promise-returning `run`, which is why [[SearchTree]] is driven from the outside rather than calling
  * an evaluator itself - see its scaladoc.
  */
trait BatchEvaluator:
  def evaluate(states: NatArray[GameState]): NatArray[Evaluation]

object BatchEvaluator:

  /** Uniform priors and a value of nothing-known.
    *
    * Turns the search into plain UCT, which is what makes it possible to test that the tree itself is
    * correct - that it finds forced wins, that visits concentrate on good moves - without a trained
    * network in the way. A bug in the search and a bad network look identical from the outside
    * otherwise.
    */
  def uninformed: BatchEvaluator = new BatchEvaluator:
    def evaluate(states: NatArray[GameState]): NatArray[Evaluation] =
      states.map(_ => Evaluation(new Array[Float](be.doeraene.mad.ai.nn.ActionIndex.teamSize), 0f))

end BatchEvaluator
