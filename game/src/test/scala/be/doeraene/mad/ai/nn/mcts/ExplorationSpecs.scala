package be.doeraene.mad.ai.nn.mcts

import scala.util.Random

import be.doeraene.mad.ai.nn.ActionIndex
import be.doeraene.mad.game.{GameBoundaries, GameState}

/** Covers the two bits of randomness self-play adds to the search.
  *
  * Both fail quietly. Noise that is not actually lumpy, or sampling that is not actually sampling, gives
  * a self-play run that trains happily on a narrow slice of the game and produces a network that stops
  * improving for no visible reason.
  */
final class ExplorationSpecs extends munit.FunSuite:

  private val boundaries = GameBoundaries.originalSixByFour
  private def start      = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = false)

  test("dirichletDrawsAreDistributions") {
    val random = Random(7)
    (0 until 200).foreach { _ =>
      val size   = 1 + random.nextInt(40)
      val sample = Dirichlet.symmetric(size, 0.3, random)
      assertEquals(sample.length, size)
      assert(sample.forall(value => value >= 0.0 && value.isFinite), sample.toList.toString)
      assertEqualsDouble(sample.sum, 1.0, 1e-9)
    }
  }

  test("aSmallAlphaIsLumpyAndALargeOneIsFlat") {
    // The property the noise is for. At alpha well below 1 a draw should put most of its mass on a few
    // components; at alpha well above 1 it should look close to uniform. A generator that got this
    // backwards would still produce valid distributions and would still pass every other check here.
    val random = Random(11)
    val size   = 20

    def averageTopMass(alpha: Double): Double =
      val trials = (0 until 300).map(_ => Dirichlet.symmetric(size, alpha, random).sorted.reverse.take(3).sum)
      trials.sum / trials.length

    val lumpy   = averageTopMass(0.3)
    val flat    = averageTopMass(20.0)
    val uniform = 3.0 / size

    assert(lumpy > 0.5, s"alpha=0.3 should concentrate, top-3 mass was $lumpy")
    assert(flat < 0.35, s"alpha=20 should spread, top-3 mass was $flat")
    assert(lumpy > flat * 1.5, s"alpha should matter: 0.3 gave $lumpy, 20 gave $flat")
    assert(flat > uniform, s"even a flat draw is not perfectly uniform, got $flat against $uniform")
  }

  test("noiseChangesTheSearchAndItsAbsenceDoesNot") {
    def visits(config: SearchConfig, seed: Long): Vector[(String, Int)] =
      val tree = SearchTree(start, config, Random(seed))
      while !tree.isDone do
        val batch = tree.selectBatch()
        if batch.nonEmpty then tree.submit(BatchEvaluator.uninformed.evaluate(batch))
      tree.rootVisits.toVector.map((action, count) => (ActionIndex.descriptor(action), count))

    val quiet = SearchConfig(simulations = 200, batchSize = 8)
    val noisy = quiet.copy(rootNoise = Some(RootNoise()))

    // Without noise the seed is irrelevant, which is what keeps benchmarks reproducible.
    assertEquals(visits(quiet, 1), visits(quiet, 2))
    // With it, different seeds explore differently.
    assertNotEquals(visits(noisy, 1), visits(noisy, 2))
    // ... but a given seed still replays exactly, or a self-play run could not be reproduced.
    assertEquals(visits(noisy, 3), visits(noisy, 3))
  }

  test("temperatureDecidesHowMuchTheMoveVaries") {
    val tree = Mcts.search(start, BatchEvaluator.uninformed, SearchConfig(simulations = 400, batchSize = 8))

    /* One generator drawn from repeatedly, which is how self-play uses it - and not a freshly seeded
     * one per draw. Java's LCG barely scrambles its seed, so Random(0), Random(1), Random(2)... all
     * return about 0.731 from their first nextDouble(); "sample 200 times with 200 seeds" would sample
     * the same bucket 200 times and look like a broken sampler. */
    val greedy = Random(1)
    val greedyPicks = (0 until 50).map(_ => tree.sampleAction(0.0, greedy)).distinct
    assertEquals(greedyPicks.length, 1, "temperature 0 should always give the same move")
    assertEquals(greedyPicks.head, tree.bestAction)

    val sampler = Random(1)
    val sampled = (0 until 400).map(_ => tree.sampleAction(1.0, sampler)).distinct
    assert(sampled.length > 5, s"temperature 1 only ever produced ${sampled.length} distinct moves")
    assert(sampled.forall(action => tree.rootVisits.exists((a, count) => a == action && count > 0)))
  }

  test("samplingFavoursTheMoveTheSearchLiked") {
    // Sampling has to be proportional, not uniform over whatever was visited at all - otherwise the
    // temperature dial does nothing and self-play is just a random-move generator with extra steps.
    val tree   = Mcts.search(start, BatchEvaluator.uninformed, SearchConfig(simulations = 600, batchSize = 8))
    val best   = tree.bestAction
    val random = Random(5)
    val picks  = (0 until 600).map(_ => tree.sampleAction(1.0, random))

    val bestShare = picks.count(_ == best).toDouble / picks.length
    val bestVisitShare =
      tree.rootVisits.find(_._1 == best).map(_._2).getOrElse(0).toDouble / tree.rootVisits.map(_._2).sum

    assertEqualsDouble(bestShare, bestVisitShare, 0.08, s"sampled $bestShare against a visit share of $bestVisitShare")
  }

end ExplorationSpecs
