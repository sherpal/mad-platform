package be.doeraene.mad.ai.nn.data

import scala.collection.parallel.CollectionConverters.*
import scala.util.Random

import be.doeraene.mad.ai.nn.mcts.{BatchEvaluator, RootNoise, SearchConfig, SearchTree}
import be.doeraene.mad.ai.nn.{ActionIndex, Canonical, StateEncoder}
import be.doeraene.mad.game.{GameBoundaries, GameState, Team}

/** Generates training data by having a network play itself, with the search as the teacher.
  *
  * This is the step that can improve on the engine it started from, and the reason for all of it. The
  * bootstrap could only ever distil the minimax; here the labels come from a search over the network's
  * own judgement, which is stronger than the network alone - measurably so, since MCTS at 3200
  * simulations beats the depth-3 engine the network was distilled from. Train on that, and the network
  * moves toward something its own search already demonstrated. Repeat.
  *
  * Two sources of variety, both necessary and neither optional:
  *
  *   - Dirichlet noise on the root priors, so the search sometimes investigates a move the network has
  *     written off. A policy that is confidently wrong has no other way of finding out.
  *   - Sampling the early moves from the visit counts rather than taking the best, so games from one
  *     opening differ. With both off, a given network plays exactly one game per opening.
  *
  * The policy target is the visit distribution, not the move played. Those differ whenever a move is
  * sampled or the noise pushed the search somewhere, and it is the visits that carry what the search
  * worked out.
  */
object SelfPlayHarvester:

  /** @param temperatureMoves
    *   how many plies to sample before switching to always taking the most-visited move. Sampling all
    *   game long would fill the set with positions reached by moves the search did not believe in.
    * @param maxPlies
    *   hard stop, so one pathological game cannot hang a generation.
    */
  final case class Config(
      games: Int,
      seed: Long,
      search: SearchConfig,
      temperatureMoves: Int = 12,
      temperature: Double = 1.0,
      maxPlies: Int = 200,
      gamesPerChunk: Int = 32
  )

  private final case class PendingSample(
      features: Array[Float],
      visits: Array[Float],
      legalActions: Array[Byte],
      rootValue: Float,
      mover: Team
  )

  def harvest(
      evaluator: BatchEvaluator,
      boundaries: GameBoundaries,
      config: Config,
      writer: ShardWriter,
      onProgress: (Int, Int, Int) => Unit = (_, _, _) => ()
  ): Unit =
    var gamesDone = 0
    (0 until config.games).grouped(config.gamesPerChunk).foreach { chunk =>
      val samples = chunk.toVector.par.map { game =>
        playGame(evaluator, boundaries, config, Random(config.seed * 1000003L + game))
      }.toVector

      samples.foreach(writer.addAll)
      gamesDone += chunk.size
      onProgress(gamesDone, config.games, writer.written)
    }

  private def playGame(
      evaluator: BatchEvaluator,
      boundaries: GameBoundaries,
      config: Config,
      random: Random
  ): Vector[TrainingSample] =
    val searchConfig = config.search.copy(rootNoise = config.search.rootNoise.orElse(Some(RootNoise())))
    var state        = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = true)
    var ply          = 0
    val pending      = Vector.newBuilder[PendingSample]

    while !state.ended && ply < config.maxPlies do
      val tree = SearchTree(state, searchConfig, random)
      while !tree.isDone do
        val batch = tree.selectBatch()
        if batch.nonEmpty then tree.submit(evaluator.evaluate(batch))

      val rootVisits = tree.rootVisits
      if rootVisits.isEmpty then ply = config.maxPlies
      else
        val visits = new Array[Float](ActionIndex.teamSize)
        val legal  = new Array[Byte](ActionIndex.teamSize)
        var index  = 0
        while index < rootVisits.length do
          val (action, count) = rootVisits(index)
          val policyIndex     = Canonical.policyIndex(state, action)
          visits(policyIndex) = count.toFloat
          legal(policyIndex) = 1
          index += 1

        /* Skipped rather than recorded wrong: canonicalising the opening plies under the special rule
         * loses the fact that the mover has yet to play. See Canonical.mirrorIsExact. */
        if Canonical.mirrorIsExact(state) then
          pending += PendingSample(
            StateEncoder.encode(state),
            visits,
            legal,
            tree.rootValue.toFloat,
            state.turnOfTeam
          )

        val temperature = if ply < config.temperatureMoves then config.temperature else 0.0
        state = tree.sampleAction(temperature, random)(state)
        ply += 1

    val winner = state.maybeWinner
    pending.result().map { sample =>
      val outcome = winner match
        case Some(team) if team == sample.mover => 1f
        case Some(_)                            => -1f
        case None                               => 0f
      TrainingSample(sample.features, sample.visits, sample.legalActions, sample.rootValue, outcome)
    }

end SelfPlayHarvester
