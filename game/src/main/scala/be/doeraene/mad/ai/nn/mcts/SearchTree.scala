package be.doeraene.mad.ai.nn.mcts

import scala.collection.mutable
import scala.util.Random

import be.doeraene.mad.ai.nn.{ActionIndex, Canonical}
import be.doeraene.mad.game.{GameAction, GameState, Team}
import be.doeraene.perf.NatArray

/** @param simulations
  *   how many leaves to visit before the search is done. The main strength/time dial.
  * @param batchSize
  *   how many leaves to gather before asking the network about them. Bigger batches keep the backend
  *   busier but make the search slightly less informed, since every leaf in a batch is chosen without
  *   knowing what the others turned out to be worth.
  * @param explorationConstant
  *   PUCT's c. Higher trusts the network's prior less and the tree's own statistics more.
  * @param virtualLoss
  *   how many pretend-lost visits to hang on an edge while a leaf below it is out for evaluation. Zero
  *   would make a whole batch take the same path.
  */
/** Exploration noise mixed into the root's priors, for self-play only.
  *
  * @param alpha
  *   Dirichlet concentration. Below 1 the draw is lumpy, which is what is wanted: a handful of moves
  *   boosted a lot, rather than every move nudged a little.
  * @param weight
  *   how much of the root prior the noise replaces.
  */
final case class RootNoise(alpha: Double = 0.3, weight: Double = 0.25)

final case class SearchConfig(
    simulations: Int = 400,
    batchSize: Int = 16,
    explorationConstant: Double = 1.4,
    virtualLoss: Int = 1,
    /** Left off for play and for benchmarking, where the strongest move is wanted and reproducibility
      * matters. Self-play turns it on; see [[RootNoise]].
      */
    rootNoise: Option[RootNoise] = None
)

/** Monte-Carlo tree search, driven from outside rather than driving itself.
  *
  * The usual shape for this is a loop that calls the network whenever it reaches a leaf. That cannot
  * work on both platforms this has to run on: in the browser the only way to run a model is a
  * promise-returning call, so a search that evaluated inline would have to be asynchronous all the way
  * down, and the JVM would pay for that structure without needing it.
  *
  * So the tree does not evaluate anything. It gathers leaves and hands them over:
  * {{{
  *   while !tree.isDone do
  *     val batch = tree.selectBatch()
  *     if batch.nonEmpty then tree.submit(evaluator.evaluate(batch))
  * }}}
  * On the JVM that loop is [[Mcts.search]]. In a worker the same loop awaits a promise instead. The
  * search itself is identical, synchronous and testable on both.
  *
  * Positions are canonicalised on the way out and moves mapped back on the way in (see [[Canonical]]),
  * so the network only ever sees red to move.
  *
  * No Dirichlet noise at the root: this plays, it does not generate training data. Self-play will want
  * it, and that is where it belongs.
  */
final class SearchTree(rootState: GameState, config: SearchConfig, random: Random = Random(0L)):

  import SearchTree.*

  private val root                            = Node(rootState)
  private var simulationsStarted              = 0
  private var simulationsFinished             = 0
  private val inFlight: mutable.ArrayBuffer[Path] = mutable.ArrayBuffer.empty

  /** True once every simulation has been backed up. Note that having started them all is not enough:
    * the last batch is still out for evaluation, and its results are what the answer is made of.
    */
  def isDone: Boolean = simulationsFinished >= config.simulations

  /** Walks down from the root up to [[SearchConfig.batchSize]] times and returns the leaves that need a
    * network evaluation.
    *
    * Terminal leaves are settled on the spot - the rules already say what they are worth, exactly, and
    * asking a network to guess at a position whose winner is known would be strictly worse. So the
    * result can be shorter than the batch size, or empty, while the search still made progress.
    */
  def selectBatch(): NatArray[GameState] =
    inFlight.clear()
    var gathered  = 0
    var gathering = true
    while gathering && gathered < config.batchSize && simulationsStarted < config.simulations do
      val path = descend()
      if inFlight.exists(_.leaf eq path.leaf) then
        /* This descent landed on a leaf already out for evaluation. Virtual loss discourages that but
         * cannot prevent it - when every alternative looks worse, the same leaf really is the best
         * choice again - and an unexpanded node has no children to descend into, so the walk stops
         * there. Keeping it would put the identical position in the batch twice and pay the network
         * for both. Roll the simulation back and send the batch early instead. */
        undo(path)
        gathering = false
      else
        simulationsStarted += 1
        if path.leaf.state.ended then
          backup(path, terminalValue(path.leaf.state))
          simulationsFinished += 1
        else
          inFlight += path
          gathered += 1
    NatArray.from(inFlight.map(_.leaf.state))

  /** Expands the leaves the last [[selectBatch]] handed out and backs their values up. */
  def submit(evaluations: NatArray[Evaluation]): Unit =
    require(
      evaluations.length == inFlight.length,
      s"expected ${inFlight.length} evaluations, got ${evaluations.length}"
    )
    var index = 0
    while index < evaluations.length do
      val path       = inFlight(index)
      val evaluation = evaluations(index)
      path.leaf.expand(evaluation.policyLogits)
      // The root is expanded like any other leaf, on the first simulation, so this is the moment its
      // priors exist and can be perturbed.
      if (path.leaf eq root) && config.rootNoise.isDefined then
        root.mixInNoise(config.rootNoise.get, random)
      backup(path, evaluation.value.toDouble)
      simulationsFinished += 1
      index += 1
    inFlight.clear()

  /** How often each of the root's moves was visited - the search's actual answer.
    *
    * Visit counts rather than the values behind them: a move that looked good once and was never
    * confirmed has a high average and means nothing, while the move the search kept coming back to is
    * the one it believes in. This is also the policy target self-play will train on.
    */
  def rootVisits: NatArray[(GameAction, Int)] =
    if !root.isExpanded then NatArray.empty[(GameAction, Int)]
    else Array.tabulate(root.actions.length)(index => (root.actions(index), root.childVisits(index)))

  /** The most-visited move at the root. */
  def bestAction: GameAction =
    val visits = rootVisits
    require(visits.nonEmpty, "the search has not expanded the root yet")
    var best  = 0
    var index = 1
    while index < visits.length do
      if visits(index)._2 > visits(best)._2 then best = index
      index += 1
    visits(best)._1

  /** What the search thinks the root position is worth, for the side to move. */
  def rootValue: Double = if root.visits == 0 then 0.0 else root.valueSum / root.visits

  /** Picks a move from the visit counts, with `temperature` deciding how much to explore.
    *
    * Zero means always the most-visited move, which is what a game being played for real wants. Self-play
    * wants the opening plies sampled instead: with a deterministic pick, a given network plays one game
    * per opening and the training set is that one game over and over.
    */
  def sampleAction(temperature: Double, sampler: Random): GameAction =
    val visits = rootVisits
    require(visits.nonEmpty, "the search has not expanded the root yet")
    if temperature <= 0.0 then bestAction
    else
      val weights = visits.map((_, count) => math.pow(count.toDouble, 1.0 / temperature))
      val total   = weights.sum
      if total <= 0.0 then bestAction
      else
        var target = sampler.nextDouble() * total
        var index  = 0
        while index < weights.length - 1 && target >= weights(index) do
          target -= weights(index)
          index += 1
        visits(index)._1

  private def descend(): Path =
    val steps = mutable.ArrayBuffer.empty[Step]
    var node  = root
    var going = true
    while going do
      if !node.isExpanded || node.state.ended then going = false
      else
        val childIndex = selectChild(node)
        node.applyVirtualLoss(childIndex, config.virtualLoss)
        steps += Step(node, childIndex)
        node = node.childAt(childIndex)
    Path(steps, node)

  private def selectChild(node: Node): Int =
    // Completed descents through this node, which is deliberately not the same as the sum of the edge
    // counts below: those include the virtual losses of simulations still out for evaluation. Keeping
    // the numerator on settled visits only means an in-flight batch discourages its own path (through
    // the per-edge term) without also inflating everyone's exploration bonus.
    val parentVisits = math.sqrt(math.max(node.visits, 1).toDouble)
    var best         = 0
    var bestScore    = Double.NegativeInfinity
    var index        = 0
    while index < node.actions.length do
      val visits = node.childVisits(index) + node.childVirtual(index)
      // An edge nobody has tried yet scores on its prior alone. Optimism would send the first few
      // simulations chasing whatever the network happened to rank highest, before any of it is checked.
      val exploitation = if visits == 0 then 0.0 else node.childValues(index) / visits
      val exploration  = config.explorationConstant * node.priors(index) * parentVisits / (1 + visits)
      val score        = exploitation + exploration
      if score > bestScore then
        bestScore = score
        best = index
      index += 1
    best

  /** Takes back the virtual losses of a descent that is not going to be used. */
  private def undo(path: Path): Unit =
    var index = 0
    while index < path.steps.length do
      val step = path.steps(index)
      step.node.releaseVirtualLoss(step.childIndex, config.virtualLoss)
      index += 1

  private def backup(path: Path, leafValue: Double): Unit =
    if path.steps.isEmpty then
      // The root itself was the leaf, which happens on the first simulation of every search. Nothing
      // below it to credit, and `recordVisit` is only ever called on a node in its role as a parent.
      root.visits += 1
      root.valueSum += leafValue
    else
      var value = leafValue
      var index = path.steps.length - 1
      while index >= 0 do
        val step = path.steps(index)
        // Mad alternates strictly, so a parent's point of view is always the opposite of its child's.
        value = -value
        step.node.releaseVirtualLoss(step.childIndex, config.virtualLoss)
        step.node.recordVisit(step.childIndex, value)
        index -= 1

  /** What a finished game is worth to the player whose turn it would be.
    *
    * A game ends either because somebody's 111 was taken or because 30 turns went by without a capture.
    * In the first case the side to move is the one that just lost its 111 - it cannot be the winner -
    * and in the second it is a draw.
    */
  private def terminalValue(state: GameState): Double = state.maybeWinner match
    case Some(winner) => if winner == state.turnOfTeam then 1.0 else -1.0
    case None         => 0.0

end SearchTree

object SearchTree:

  private final case class Step(node: Node, childIndex: Int)
  private final case class Path(steps: mutable.ArrayBuffer[Step], leaf: Node)

  /** A position in the tree.
    *
    * Edge statistics live in parallel arrays on the parent rather than in child objects: the selection
    * loop reads every edge of a node on every single descent, and a search does that hundreds of
    * thousands of times.
    */
  private final class Node(val state: GameState):
    var visits: Int      = 0
    var valueSum: Double = 0.0

    var actions: NatArray[GameAction] = NatArray.empty[GameAction]
    var priors: Array[Float]          = Array.empty
    var childVisits: Array[Int]       = Array.empty
    var childValues: Array[Double]    = Array.empty
    var childVirtual: Array[Int]      = Array.empty
    // Children are created on first visit, so this holds nulls for edges never taken. An Option per
    // edge would allocate on a path walked hundreds of thousands of times per search.
    private var children: Array[Node] = Array.empty

    private var expanded: Boolean = false
    def isExpanded: Boolean       = expanded

    def expand(policyLogits: Array[Float]): Unit =
      if !expanded then
        actions = state.allValidActions
        val count = actions.length
        priors = priorsFrom(policyLogits, state, actions)
        childVisits = new Array[Int](count)
        childValues = new Array[Double](count)
        childVirtual = new Array[Int](count)
        children = new Array[Node](count)
        expanded = true

    /** Replaces part of the prior with a Dirichlet draw, in place. */
    def mixInNoise(noise: RootNoise, random: Random): Unit =
      val drawn = Dirichlet.symmetric(priors.length, noise.alpha, random)
      var index = 0
      while index < priors.length do
        priors(index) = ((1.0 - noise.weight) * priors(index) + noise.weight * drawn(index)).toFloat
        index += 1

    def childAt(index: Int): Node =
      val existing = children(index)
      if existing ne null then existing
      else
        val created = Node(actions(index)(state))
        children(index) = created
        created

    def applyVirtualLoss(index: Int, amount: Int): Unit =
      childVirtual(index) += amount
      // Counted as losses so a second descent avoids this edge rather than piling onto it.
      childValues(index) -= amount

    def releaseVirtualLoss(index: Int, amount: Int): Unit =
      childVirtual(index) -= amount
      childValues(index) += amount

    def recordVisit(index: Int, value: Double): Unit =
      childVisits(index) += 1
      childValues(index) += value
      visits += 1
      valueSum += value
  end Node

  /** Softmax of the network's logits over the legal moves only.
    *
    * Masking before the softmax rather than after: the network is never trained to rank moves that
    * cannot be played, so whatever it says about them is noise, and letting that noise into the
    * normaliser would quietly shrink every real prior by an arbitrary amount.
    */
  private def priorsFrom(
      policyLogits: Array[Float],
      state: GameState,
      actions: NatArray[GameAction]
  ): Array[Float] =
    val count  = actions.length
    val priors = new Array[Float](count)

    var largest = Float.NegativeInfinity
    var index   = 0
    while index < count do
      val logit = policyLogits(Canonical.policyIndex(state, actions(index)))
      priors(index) = logit
      if logit > largest then largest = logit
      index += 1

    var total = 0.0
    index = 0
    while index < count do
      val weight = math.exp((priors(index) - largest).toDouble)
      priors(index) = weight.toFloat
      total += weight
      index += 1

    index = 0
    while index < count do
      priors(index) = (priors(index) / total).toFloat
      index += 1
    priors

end SearchTree
