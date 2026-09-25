package be.doeraene.mad.ai.benchmark

import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.Player.MadPlayer
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState, Team}
import be.doeraene.perf.NatArray

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.parallel.CollectionConverters.*

/** Plays a whole battery of games between two AI configurations and reports an aggregate score.
  *
  * A single `ai-match` game is worthless as a strength measurement: nothing in the engine is random, so one (playerA,
  * playerB, colour) triple always replays the exact same game. One win proves that one line of play works, not that an
  * evaluator is better. What this does instead is use the game's own diversification mechanism - the positioning turn,
  * where each side may permute, rotate or stand pat before the game proper starts (9 choices per side, so 81 distinct
  * openings) - as a battery of starting positions, and play every opening with both colour assignments so a result can
  * never come from having been handed the better side.
  */
object Benchmark:

  /** One game of the battery: an opening (as the pair of positioning-turn actions both sides are forced into), which
    * colour the candidate plays, and the board it is played on.
    *
    * The board is carried by the game rather than passed alongside it, so a battery built for one board
    * cannot be played on another - which is otherwise an easy mistake to make and a silent one, since
    * the positioning-turn actions are board-independent and would apply happily to the wrong board.
    */
  final case class BatteryGame(
      redOpening: GameAction,
      blueOpening: GameAction,
      candidateIsRed: Boolean,
      boundaries: GameBoundaries
  )

  /** The positioning-turn actions available to a team: stand pat, any permutation, any rotation. Deliberately taken
    * from the engine's own legality check rather than hard-coded, so this can't drift from the rules.
    */
  private def openingActions(team: Team, boundaries: GameBoundaries): NatArray[GameAction] =
    val start  = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = true)
    val forRed = start.allValidActions
    if team == Team.Red then forRed else forRed.head(start).allValidActions

  /** @param skip
    *   how many openings of the shuffled order to drop before taking [[openingCount]]. Two batteries built from the
    *   same seed with `skip = 0` and `skip = openingCount` are disjoint, which is what lets a tuner hill-climb on one
    *   slice and be honestly validated on another.
    */
  def battery(
      openingCount: Int,
      seed: Long,
      skip: Int = 0,
      boundaries: GameBoundaries = GameBoundaries.originalSixByFour
  ): List[BatteryGame] =
    val random      = new scala.util.Random(seed)
    val redOptions  = openingActions(Team.Red, boundaries).toVector
    val blueOptions = openingActions(Team.Blue, boundaries).toVector
    val allPairs = (for {
      red  <- redOptions
      blue <- blueOptions
    } yield (red, blue)).toList
    val chosen =
      if skip == 0 && openingCount >= allPairs.size then allPairs
      else random.shuffle(allPairs).slice(skip, skip + openingCount)
    chosen.flatMap((red, blue) =>
      List(BatteryGame(red, blue, true, boundaries), BatteryGame(red, blue, false, boundaries))
    )

  final case class GameOutcome(candidateScore: Double, turns: Int)

  /** Score from the candidate's point of view: 1 for a win, 0 for a loss, 0.5 for the 30-turns-without-an-exile draw.
    */
  private def outcomeScore(candidateTeam: Team, maybeWinner: Option[Team]): Double = maybeWinner match
    case Some(team) if team == candidateTeam => 1.0
    case Some(_)                             => 0.0
    case None                                => 0.5

  def playOne(
      candidate: MadPlayer,
      opponent: MadPlayer,
      game: BatteryGame
  ): GameOutcome =
    val start         = GameState.initialGameStateWithBoundaries(game.boundaries, withInitialSpecialRule = true)
    val afterOpening  = game.blueOpening(game.redOpening(start))
    val redPlayer     = if game.candidateIsRed then candidate else opponent
    val bluePlayer    = if game.candidateIsRed then opponent else candidate
    val candidateTeam = if game.candidateIsRed then Team.Red else Team.Blue

    val history = Player.playMadGame(redPlayer, bluePlayer, afterOpening, verbose = false)
    val last    = history.last
    GameOutcome(outcomeScore(candidateTeam, last.maybeWinner), last.turnNumber)

  final case class Report(wins: Int, losses: Int, draws: Int, averageTurns: Double):
    def games: Int      = wins + losses + draws
    def score: Double   = wins + 0.5 * draws
    def winRate: Double = score / games
    def pretty(candidateName: String, opponentName: String): String =
      f"$candidateName%s vs $opponentName%s: ${score}%.1f/$games%d " +
        f"(${winRate * 100}%.1f%%)  W$wins L$losses D$draws  avg ${averageTurns}%.1f turns"

  /** Runs the whole battery, one game per core. Games are completely independent, and a depth-3 game takes seconds, so
    * this is where the wall-clock of an experiment is actually decided.
    */
  def run(
      candidate: MadPlayer,
      opponent: MadPlayer,
      games: List[BatteryGame],
      onProgress: (Int, Int) => Unit = (_, _) => ()
  ): Report =
    val done = new AtomicInteger(0)
    val outcomes = games.par.map { game =>
      val outcome = playOne(candidate, opponent, game)
      onProgress(done.incrementAndGet(), games.size)
      outcome
    }.toList

    Report(
      wins = outcomes.count(_.candidateScore == 1.0),
      losses = outcomes.count(_.candidateScore == 0.0),
      draws = outcomes.count(_.candidateScore == 0.5),
      averageTurns = outcomes.map(_.turns.toDouble).sum / outcomes.size
    )

end Benchmark
