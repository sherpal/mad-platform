package be.doeraene.mad.ai.tuning

import be.doeraene.mad.ai.Player.MadPlayer
import be.doeraene.mad.ai.benchmark.Benchmark
import be.doeraene.mad.ai.{Player, TacticalEvaluator, TacticalWeights}
import be.doeraene.mad.game.{GameState, Team}

import scala.collection.parallel.CollectionConverters.*

/** Fits [[TacticalWeights]] by supervised regression on labelled positions, rather than by playing games with each
  * candidate.
  *
  * [[TacticalWeightTuner]] scores a candidate by playing a battery and counting wins, and that objective turned out
  * to be unusable at any battery size affordable per candidate: 40 games carries roughly a 15-point spread, so a
  * hill climb spends its rounds chasing which openings happen to fall its way. Two runs confirmed it - each gained
  * several games on the slice it could see and lost more on the slice it could not.
  *
  * This is the standard answer to that problem (Texel tuning, from computer chess). Play a fixed set of games *once*,
  * keep every position along with the result the game eventually reached, and then score a candidate by how well its
  * static evaluation predicts those results. The objective becomes a smooth function over tens of thousands of
  * labelled samples instead of a step function over a few dozen coin flips, and - decisively - evaluating it costs
  * milliseconds rather than minutes, because no games are played. That turns a search that could afford ~40 noisy
  * probes into one that can afford thousands of precise ones.
  *
  * The usual caveat applies: this optimises *prediction of the outcome*, which is a proxy for playing strength, not
  * strength itself. The fitted weights still have to be confirmed on a real battery at the depth they will play at.
  */
object TexelTuner:

  /** A position, and the result the game it came from eventually reached, always from [[Team.Red]]'s point of view. */
  final case class Sample(gameState: GameState, result: Double)

  /** Plays the battery once and keeps every position from it.
    *
    * Early plies are dropped: they are shared by whole families of games and their label is almost pure noise about
    * the opening rather than signal about the evaluation. Terminal positions are dropped too - they are settled by
    * [[be.doeraene.mad.ai.minimax.TreeExplorer.exactScore]] and never reach the evaluator in a real search.
    */
  def collectSamples(
      candidate: MadPlayer,
      opponent: MadPlayer,
      games: List[Benchmark.BatteryGame],
      skipPlies: Int = 6
  ): List[Sample] =
    games.par.flatMap { game =>
      val start        = GameState.initial6By4GameState(true)
      val afterOpening = game.blueOpening(game.redOpening(start))
      val redPlayer    = if game.candidateIsRed then candidate else opponent
      val bluePlayer   = if game.candidateIsRed then opponent else candidate

      val history = Player.playMadGame(redPlayer, bluePlayer, afterOpening, verbose = false)
      /* Always from Red's point of view, matching how `loss` reads the evaluation back out. `Team` is not sealed,
       * so this tests the winner rather than pattern-matching both cases. */
      val result = history.last.maybeWinner match
        case Some(winner) => if winner == Team.Red then 1.0 else 0.0
        case None         => 0.5

      history.drop(skipPlies).filterNot(_.ended).map(Sample(_, result))
    }.toList

  /** Squared error of the evaluation's implied win probability against the actual results.
    *
    * The evaluation is in material-ish units and the label is a probability, so the two are bridged by a logistic
    * with scale `k` - the eval difference that corresponds to a decisive advantage. `k` is fitted alongside the
    * weights rather than guessed, since it is exactly as arbitrary as they are.
    */
  def loss(weights: TacticalWeights, k: Double, samples: Array[Sample]): Double =
    val scorer = new TacticalEvaluator.Scorer(weights)
    var total  = 0.0
    var index  = 0
    while index < samples.length do
      val sample     = samples(index)
      val evaluation = scorer.score(sample.gameState, Team.Red)
      val predicted  = 1.0 / (1.0 + math.exp(-evaluation / k))
      val error      = sample.result - predicted
      total += error * error
      index += 1
    total / samples.length

  /** The full parameter vector the fit moves: the eighteen weights, plus the logistic scale that bridges evaluation
    * units to probabilities. The scale is fitted rather than guessed, since it is exactly as arbitrary as the rest.
    */
  private type Params = (TacticalWeights, Double)

  private val parameters: List[(String, Params => Double, (Params, Double) => Params)] =
    ("logisticScale", (p: Params) => p._2, (p: Params, v: Double) => (p._1, v)) ::
      TacticalWeights.tunable.map { (name, getter, setter) =>
        (name, (p: Params) => getter(p._1), (p: Params, v: Double) => (setter(p._1, v), p._2))
      }

  /** Bounds for the parameters that stop meaning what they mean outside a range.
    *
    * [[TacticalWeights.drawFear]] scales a positive score by `1 - drawFear * turnsSinceLastPieceDied / 30`, so above
    * 1.0 that factor goes negative before the tie-break arrives and a winning position starts evaluating as a losing
    * one - the fit will happily go there, because the regression only sees positions and not the play that follows
    * them. The rest are fractions by construction: they discount a threat the side to move still gets to answer.
    */
  private val upperBound: Map[String, Double] = Map(
    "drawFear"            -> 1.0,
    "rescueFactor"        -> 1.0,
    "corvetteTempoRelief" -> 1.0,
    "recallCredit"        -> 1.0
  )

  /** Floors for the material scale, so no ship can end up worth nothing.
    *
    * An unconstrained fit drives [[TacticalWeights.materialBase]], [[TacticalWeights.defenceBonus]] and
    * [[TacticalWeights.movementBonus]] to zero and loads everything onto [[TacticalWeights.attackBonus]], which
    * prices 112, 211 and 212 at exactly nothing. That fits the data - "has attack-2 ships alive" predicts winning
    * perfectly well - and it even plays well against an engine, because the exchange term still resolves captures.
    * But it means nothing in the evaluation objects to handing those three ships over, and a human opponent who
    * notices will take them for free in a way no engine in the battery ever tried.
    *
    * These floors are a judgement call, not a fitted quantity: they are set so that the weakest ship keeps a value
    * somewhere around a sixth of the cruiser's, rather than zero. The regression is free to go above them.
    */
  private val lowerBound: Map[String, Double] = Map(
    "materialBase"  -> 1.5,
    "defenceBonus"  -> 0.8,
    "movementBonus" -> 0.5
  )

  final case class SweepResult(pass: Int, parameter: String, from: Double, to: Double, trainingLoss: Double)

  /** Coordinate descent: sweep every parameter, trying a ladder of multiplicative steps and keeping the best.
    *
    * Derivative-free on purpose. The evaluation is not linear in these weights - [[TacticalWeights.recallCredit]]
    * scales a quantity built out of the material weights, and the hanging term takes a max over piece values before
    * scaling - so there is no clean analytic gradient to descend, and with a loss this cheap to evaluate there is no
    * need for one.
    */
  def fit(
      training: Array[Sample],
      validation: Array[Sample],
      startingWeights: TacticalWeights = TacticalWeights.default,
      startingScale: Double = 4.0,
      passes: Int = 12,
      ladder: List[Double] = List(0.5, 0.7, 0.85, 0.95, 1.05, 1.2, 1.5, 2.0)
  )(onSweep: SweepResult => Unit): (TacticalWeights, Double) =
    var params      = (startingWeights, startingScale)
    var currentLoss = loss(params._1, params._2, training)

    for pass <- 1 to passes do
      parameters.foreach { (name, getter, setter) =>
        val currentValue = getter(params)
        var bestValue    = currentValue
        var bestLoss     = currentLoss

        ladder.foreach { factor =>
          val floor = lowerBound.getOrElse(name, 1e-4)
          val candidateValue =
            math.min(upperBound.getOrElse(name, Double.MaxValue), math.max(floor, currentValue * factor))
          val candidate      = setter(params, candidateValue)
          val candidateLoss  = loss(candidate._1, candidate._2, training)
          if candidateLoss < bestLoss then
            bestLoss = candidateLoss
            bestValue = candidateValue
        }

        if bestValue != currentValue then
          params = setter(params, bestValue)
          currentLoss = bestLoss
          onSweep(SweepResult(pass, name, currentValue, bestValue, bestLoss))
      }

    val heldOut = loss(params._1, params._2, validation)
    println(f"texel fit: training loss ${currentLoss}%.6f, held-out loss ${heldOut}%.6f, scale ${params._2}%.3f")
    (params._1, params._2)

end TexelTuner
