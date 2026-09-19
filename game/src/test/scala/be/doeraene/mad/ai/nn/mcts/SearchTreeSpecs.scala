package be.doeraene.mad.ai.nn.mcts

import be.doeraene.mad.ai.nn.{ActionIndex, Canonical}
import be.doeraene.mad.game.*
import be.doeraene.perf.NatArray

/** Checks the search itself, with the network replaced by [[BatchEvaluator.uninformed]].
  *
  * Everything here would also pass or fail for reasons to do with a trained network, and from the
  * outside the two are indistinguishable - a search that backs values up with the wrong sign and a
  * network that has learnt nothing both produce an engine that plays badly. So the network is taken out
  * of the picture: with flat priors and a value of zero this is plain UCT, and the only thing that can
  * make it find a win is the tree being right.
  */
final class SearchTreeSpecs extends munit.FunSuite:

  private val boundaries = GameBoundaries.originalSixByFour

  private def at(row: Int, col: Int): boundaries.Position =
    boundaries.Position(row, col).getOrElse(fail(s"($row, $col) is not a square"))

  /** Red to move, with red221 one step to the left of blue111 and able to take it.
    *
    * 221 attacks at 2 and the corvette defends at 1, so the capture is legal and ends the game at once.
    * red111 is parked in its corner, out of everyone's way, so nothing else is going on.
    */
  private def redWinsInOne: GameState = GameState(boundaries)(
    Map(
      GamePiece.red111 -> at(5, 1),
      GamePiece.red221 -> at(2, 1),
      GamePiece.red212 -> at(4, 3),
      GamePiece.blue111 -> at(2, 2),
      GamePiece.blue222 -> at(0, 0),
      GamePiece.blue112 -> at(1, 3)
    ),
    turnNumber = 11,
    turnsSinceLastPieceDied = 3,
    withInitialSpecialRule = false
  )

  private val winningMove = GameAction.GamePieceMoves1(GamePiece.red221, Positions.right)

  private def config(simulations: Int) = SearchConfig(simulations = simulations, batchSize = 8)

  test("takesTheWinWhenThereIsOne") {
    val state = redWinsInOne
    assert(state.allValidActions.contains(winningMove), "the position does not offer the capture")
    assertEquals(state.turnOfTeam, Team.Red)

    val tree = Mcts.search(state, BatchEvaluator.uninformed, config(400))

    assertEquals(tree.bestAction, winningMove: GameAction)
    assert(tree.rootValue > 0.5, s"a won position should look won, got ${tree.rootValue}")
  }

  test("theWinningMoveIsTheOneItKeepsComingBackTo") {
    val tree   = Mcts.search(redWinsInOne, BatchEvaluator.uninformed, config(400))
    val visits = tree.rootVisits.toVector
    val winner = visits.find(_._1 == winningMove).map(_._2).getOrElse(0)
    val rest   = visits.filterNot(_._1 == winningMove).map(_._2).maxOption.getOrElse(0)

    assert(winner > rest * 3, s"the winning move got $winner visits, the next best got $rest")
  }

  /** The same position seen from the other side.
    *
    * This is the test that the canonicalisation survives contact with the search: every position handed
    * to an evaluator is mirrored to red-to-move, and every prior has to be mapped back onto the moves of
    * whoever is actually playing. Get that mapping wrong and the search still runs, still returns a
    * move, and is quietly reading someone else's priors.
    */
  test("playsTheSameWayFromTheOtherSide") {
    val mirrored = redWinsInOne.mirrored
    assertEquals(mirrored.turnOfTeam, Team.Blue)

    val tree = Mcts.search(mirrored, BatchEvaluator.uninformed, config(400))

    assertEquals(tree.bestAction, ActionIndex.mirror(winningMove))
    assert(tree.rootValue > 0.5, s"a won position should look won from either side, got ${tree.rootValue}")
  }

  /** An evaluator that loves one particular move and is indifferent to everything else.
    *
    * With no tactics in the position there is nothing for the tree's own statistics to latch onto, so
    * where the visits go is entirely down to the priors - which means this catches a prior being read
    * at the wrong index, the one bug that would otherwise just look like a weak network.
    */
  private def favouring(state: GameState, action: GameAction): BatchEvaluator = new BatchEvaluator:
    private val favoured = Canonical.policyIndex(state, action)
    def evaluate(states: NatArray[GameState]): NatArray[Evaluation] = states.map { _ =>
      val logits = new Array[Float](ActionIndex.teamSize)
      logits(favoured) = 8f
      Evaluation(logits, 0f)
    }

  test("followsThePriorItIsGiven") {
    val state   = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = false)
    val actions = state.allValidActions.toVector
    // Not the first action, which several tie-breaks would land on by accident.
    val target = actions(actions.length / 2)

    val tree   = Mcts.search(state, favouring(state, target), config(200))
    val visits = tree.rootVisits.toVector

    assertEquals(tree.bestAction, target)
    val favoured = visits.find(_._1 == target).map(_._2).getOrElse(0)
    val rest     = visits.filterNot(_._1 == target).map(_._2).maxOption.getOrElse(0)
    assert(favoured > rest * 3, s"the favoured move got $favoured visits, the next got $rest")
  }

  test("everySimulationIsAccountedFor") {
    val simulations = 300
    val tree        = Mcts.search(redWinsInOne, BatchEvaluator.uninformed, config(simulations))

    // One simulation expands the root and never descends, so the edges below it share the rest.
    assertEquals(tree.rootVisits.map(_._2).sum, simulations - 1)
  }

  test("isDeterministic") {
    // Nothing here is random, and a search that quietly was could not be benchmarked: two runs of the
    // same battery would differ for reasons that have nothing to do with the change being measured.
    def run(): Vector[(String, Int)] =
      Mcts
        .search(redWinsInOne, BatchEvaluator.uninformed, config(250))
        .rootVisits
        .toVector
        .map((action, visits) => (ActionIndex.descriptor(action), visits))

    assertEquals(run(), run())
  }

  test("batchingChangesSpeedRatherThanAnswers") {
    // Bigger batches mean more leaves chosen without knowing what their siblings were worth, so the
    // visit counts legitimately differ - but the conclusion should not.
    val single  = Mcts.search(redWinsInOne, BatchEvaluator.uninformed, SearchConfig(400, batchSize = 1))
    val batched = Mcts.search(redWinsInOne, BatchEvaluator.uninformed, SearchConfig(400, batchSize = 32))

    assertEquals(single.bestAction, winningMove: GameAction)
    assertEquals(batched.bestAction, winningMove: GameAction)
    assertEquals(single.rootVisits.map(_._2).sum, batched.rootVisits.map(_._2).sum)
  }

  test("aFinishedGameNeedsNoNetwork") {
    // The rules already say exactly what a terminal position is worth, so the evaluator should never be
    // asked about one. This matters beyond tidiness: a network's guess at a position whose winner is
    // known would be strictly worse information.
    val over = GameState(boundaries)(
      Map(GamePiece.red111 -> at(5, 1), GamePiece.red221 -> at(2, 2), GamePiece.blue222 -> at(0, 0)),
      turnNumber = 12,
      turnsSinceLastPieceDied = 3,
      withInitialSpecialRule = false
    )
    assert(over.ended, "expected a position with blue's 111 already gone")

    val tree = SearchTree(over, config(50))
    assertEquals(tree.selectBatch().length, 0)
    assert(tree.isDone, "a search from a finished position has nothing to do")
  }

end SearchTreeSpecs
