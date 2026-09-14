package be.doeraene.mad.ai.tuning

import be.doeraene.mad.ai.benchmark.Benchmark
import be.doeraene.mad.ai.{Player, TacticalWeights}
import be.doeraene.mad.ai.Player.MadPlayer

import scala.util.Random

/** Local search over [[TacticalWeights]], scored on a real battery rather than a handful of fixed games.
  *
  * This is the same shape as [[ClaudeWeightTuner]] but with the objective fixed. That tuner scored a candidate on
  * four games - jPaul's theory at `aValue` 0.02 and 0.05, both colours - and `aValue` turns out to be dead code in
  * [[be.doeraene.mad.game.PieceEvaluator.jPaulDoeTheoryWithTarget]] (the parameter is never read), so those four
  * games were really two, replayed. Nothing in the engine is random, so two games is two samples, and weights tuned
  * against them win those two games and little else: the resulting [[be.doeraene.mad.ai.ClaudeWeights]] scores 8/8
  * on its own battery and 38.8% over a diversified one.
  *
  * So the objective here is a [[Benchmark]] battery of distinct positioning-turn openings, each played with both
  * colour assignments, and it is deliberately *sliced*: the tuner hill-climbs on openings `[0, openings)` of a
  * shuffle and the caller is expected to validate the result on a disjoint slice of the same shuffle. Without that,
  * there is no way to tell a genuine improvement from a set of weights that has memorised thirty openings.
  *
  * Two cautions, both learned by running this and getting it wrong.
  *
  * **Tune at the depth you play at.** A depth-2 climb found weights worth 69.2% against jPaul at depth 2, on
  * openings it had never seen - and 43.8% at depth 3, below the 48.8% of the untuned values it started from.
  * `alphaBeta` stops on a node where the opponent is to move at even depths and where we are at odd ones, and the
  * tempo terms in [[be.doeraene.mad.ai.TacticalEvaluator]] exist precisely to tell those apart.
  *
  * **40 games is under the noise floor.** A 35-round depth-3 climb over a 20-opening slice went from 19.5/40 to
  * 25.5/40 on that slice and from 25.5/40 to 16.5/40 on the disjoint one. The tell is in the untuned numbers: the
  * same default weights score 19.5 on one slice and 25.5 on the other, so a battery that size carries roughly a
  * 15-point spread and a hill climb has ample room to chase it for 35 rounds without touching real strength. Read
  * only the held-out slice, treat it as the entire result rather than as confirmation of the training one, and if
  * the search is to resolve anything finer, widen the battery well past 40 games per candidate.
  */
object TacticalWeightTuner:

  final case class StepResult(
      iteration: Int,
      fieldsTried: List[String],
      triedScore: Double,
      accepted: Boolean,
      currentBest: TacticalWeights,
      currentBestScore: Double
  )

  /** Weights that must stay inside a range to keep meaning what they mean. `drawFear` scales a positive score by
    * `1 - drawFear * turnsSinceLastPieceDied / 30`, so anything above 1.0 would flip a winning position's sign
    * partway to the tie-break and have the evaluator chase the draw it is supposed to be avoiding; the factors that
    * discount a threat the side to move can answer are fractions by construction.
    */
  private val upperBound: Map[String, Double] = Map(
    "drawFear"            -> 1.0,
    "rescueFactor"        -> 1.0,
    "corvetteTempoRelief" -> 1.0,
    "recallCredit"        -> 1.0
  )

  def score(
      weights: TacticalWeights,
      minimaxDepth: Int,
      opponent: MadPlayer,
      games: List[Benchmark.BatteryGame]
  ): Double =
    Benchmark.run(Player.tacticalPlayerWithWeights(minimaxDepth, weights), opponent, games).score

  /** Plain (1+1) hill climbing: perturb one or two weights multiplicatively, replay the battery, keep the change
    * unless it made things worse. The objective is deterministic but piecewise-constant - most small changes move
    * nothing at all until one flips a tie-break somewhere - so accepting ties (`>=`) matters: it lets the search
    * drift across the flat parts instead of stalling on the first plateau it lands on.
    */
  def hillClimb(
      iterations: Int,
      minimaxDepth: Int,
      opponent: MadPlayer,
      games: List[Benchmark.BatteryGame],
      startingWeights: TacticalWeights = TacticalWeights.default,
      seed: Long = 42L
  )(onStep: StepResult => Unit): (TacticalWeights, Double) =
    val random = new Random(seed)

    var current      = startingWeights
    var currentScore = score(current, minimaxDepth, opponent, games)

    for iteration <- 1 to iterations do
      val fieldCount = if random.nextInt(3) == 0 then 2 else 1
      val fields     = random.shuffle(TacticalWeights.tunable).take(fieldCount)

      val tried = fields.foldLeft(current) { case (weights, (name, getter, setter)) =>
        // multiplicative, with a floor so a weight that has collapsed towards 0 can still climb back out
        val factor = 0.6 + random.nextDouble() * 0.8
        val raw    = math.max(0.01, getter(weights) * factor)
        setter(weights, math.min(raw, upperBound.getOrElse(name, Double.MaxValue)))
      }

      val triedScore = score(tried, minimaxDepth, opponent, games)
      val accepted   = triedScore >= currentScore

      if accepted then
        current = tried
        currentScore = triedScore

      onStep(StepResult(iteration, fields.map(_._1), triedScore, accepted, current, currentScore))

    (current, currentScore)

end TacticalWeightTuner
