package be.doeraene.mad.ai.tuning

import be.doeraene.mad.ai.{ClaudeWeights, Player}
import be.doeraene.mad.game.{GameState, Team}

import scala.util.Random

/** A small local-search tuner for [[ClaudeWeights]].
  *
  * The objective is a fixed "battery" of deterministic games: the candidate weights, playing both colours, against
  * a handful of fixed opponents (jPaul's theory at a couple of `aValue`s). Because there is no randomness anywhere
  * in the engine's move choice, a given (weights, opponent, colour) triple always plays out to the exact same
  * result - unlike typical self-play tuning, there is no need to repeat a matchup to average out noise, only to vary
  * the opponents and colours enough that the total score is a meaningful proxy for strength.
  *
  * The search itself is plain (1+1)-style hill-climbing: repeatedly perturb one randomly-chosen weight by a random
  * multiplicative factor, replay the whole battery, and keep the change only if it does not make the score worse.
  * This is an honest, simple choice given the objective is piecewise-constant (a small weight change often changes
  * nothing at all until it flips some tie-break) rather than smooth - not a claim that it's the best possible tuner.
  * A natural upgrade path, if this proves too easily stuck, is simulated annealing (accept a worse score sometimes,
  * with probability shrinking over time) or widening the battery.
  */
object ClaudeWeightTuner:

  /** One battery game: the candidate plays [[candidateIsRed]], against jPaul's theory at [[opponentAValue]]. */
  final case class Matchup(opponentAValue: Double, candidateIsRed: Boolean)

  val defaultBattery: List[Matchup] = List(
    Matchup(0.02, candidateIsRed = true),
    Matchup(0.02, candidateIsRed = false),
    Matchup(0.05, candidateIsRed = true),
    Matchup(0.05, candidateIsRed = false)
  )

  private def outcomeScore(candidateTeam: Team, maybeWinner: Option[Team]): Double = maybeWinner match {
    case Some(team) if team == candidateTeam => 1.0
    case Some(_)                             => 0.0
    case None                                => 0.5
  }

  /** Plays the whole battery for one candidate set of weights and returns its total score (max = battery.size). */
  def score(weights: ClaudeWeights, minimaxDepth: Int, battery: List[Matchup] = defaultBattery): Double =
    battery.map { matchup =>
      val candidate = Player.claudeTheoryPlayerWithWeights(minimaxDepth, weights)
      val opponent  = Player.jPaulTheoryPlayer(minimaxDepth, matchup.opponentAValue)

      val redPlayer     = if matchup.candidateIsRed then candidate else opponent
      val bluePlayer    = if matchup.candidateIsRed then opponent else candidate
      val candidateTeam = if matchup.candidateIsRed then Team.Red else Team.Blue

      val history = Player.playMadGame(redPlayer, bluePlayer, GameState.initial6By4GameState(true), verbose = false)
      outcomeScore(candidateTeam, history.last.maybeWinner)
    }.sum

  /** Reported after every hill-climbing round, so a caller can log progress as it happens rather than waiting for
    * the whole run to finish.
    */
  final case class StepResult(
      iteration: Int,
      fieldTried: String,
      oldValue: Double,
      triedValue: Double,
      scoreBefore: Double,
      triedScore: Double,
      accepted: Boolean,
      currentBest: ClaudeWeights,
      currentBestScore: Double
  )

  /** Runs [[iterations]] rounds of hill-climbing starting from [[startingWeights]] and returns the best weights found
    * together with their score. [[onStep]] is called after every round for progress logging.
    */
  def hillClimb(
      iterations: Int,
      minimaxDepth: Int,
      startingWeights: ClaudeWeights = ClaudeWeights.default,
      battery: List[Matchup] = defaultBattery,
      seed: Long = 42L
  )(onStep: StepResult => Unit): (ClaudeWeights, Double) =
    val random = new Random(seed)

    var current      = startingWeights
    var currentScore = score(current, minimaxDepth, battery)

    for iteration <- 1 to iterations do
      val (name, getter, setter) = ClaudeWeights.tunable(random.nextInt(ClaudeWeights.tunable.length))
      val oldValue = getter(current)
      // multiplicative perturbation in [0.6, 1.4], with a floor so a weight can still escape from (near) 0
      val factor         = 0.6 + random.nextDouble() * 0.8
      val triedValue     = math.max(0.01, oldValue * factor)
      val triedWeights   = setter(current, triedValue)
      val scoreBefore    = currentScore
      val triedScore     = score(triedWeights, minimaxDepth, battery)
      val accepted       = triedScore >= scoreBefore

      if accepted then
        current = triedWeights
        currentScore = triedScore

      onStep(StepResult(iteration, name, oldValue, triedValue, scoreBefore, triedScore, accepted, current, currentScore))

    (current, currentScore)

end ClaudeWeightTuner
