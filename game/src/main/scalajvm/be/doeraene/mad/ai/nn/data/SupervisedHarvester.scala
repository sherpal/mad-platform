package be.doeraene.mad.ai.nn.data

import scala.collection.parallel.CollectionConverters.*
import scala.util.Random

import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}
import be.doeraene.mad.ai.nn.{ActionIndex, Canonical, StateEncoder}
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState, Team}

/** Harvests labelled positions by watching the existing minimax engine play itself.
  *
  * This is the bootstrap that comes before any self-play: distilling the hand-tuned evaluator into a network gives a
  * warm start worth days of self-play, and - more to the point at this stage - it exercises the whole pipeline
  * (encoding, shards, training, ONNX export, browser inference) against a baseline whose strength is already known. A
  * network that cannot learn to imitate the depth-4 engine has a bug in it somewhere, and that is a far easier thing to
  * debug than a self-play run that simply never improves.
  *
  * The engine is deterministic, so left alone it would replay the same game forever. Diversity comes from two places:
  *
  *   - a random opening of a few plies, which is where most of the spread comes from;
  *   - an exploration rate, which plays a uniformly random legal move every so often thereafter.
  *
  * In both cases the *label* is still the engine's own evaluation of the position, not the move actually played -
  * `actionsAndScores` is called at every recorded ply regardless of what happens next. Labelling a position with a
  * random move would be teaching the network to play randomly.
  */
object SupervisedHarvester:

  /** @param games
    *   how many games to play. Each contributes roughly one sample per ply.
    * @param maxRandomOpeningPlies
    *   upper bound on the random prefix; the actual length is drawn uniformly below it. Positions inside the prefix are
    *   not recorded, only the positions the game reaches afterwards.
    * @param explorationRate
    *   probability of playing a random legal move instead of the engine's choice, after the opening. Keep it small:
    *   every random move makes the game's eventual result a noisier label for the positions before it.
    * @param maxPlies
    *   hard stop, so a pathological game cannot hang a harvest.
    * @param gamesPerChunk
    *   how many games to play between writes. Bounds how many samples are held in memory at once.
    */
  final case class Config(
      games: Int,
      seed: Long,
      minimaxDepth: Int,
      maxRandomOpeningPlies: Int = 8,
      explorationRate: Double = 0.05,
      maxPlies: Int = 200,
      gamesPerChunk: Int = 64
  )

  /** A recorded position, waiting for the game to finish so its [[TrainingSample.outcome]] can be filled in. */
  private final case class PendingSample(
      features: Array[Float],
      actionScores: Array[Float],
      legalActions: Array[Byte],
      rootScore: Float,
      mover: Team
  )

  def harvest(
      treeExplorer: TreeExplorer.MadTreeExplorer,
      boundaries: GameBoundaries,
      config: Config,
      writer: ShardWriter,
      onProgress: (Int, Int, Int) => Unit = (_, _, _) => ()
  ): Unit =
    var gamesDone = 0
    /* Parallel over games rather than inside the search. `Node.actionsAndScores` already spreads a single position's
     * root moves over the pool, but a whole game is a far coarser unit of work and keeps every core busy for the
     * length of a game instead of for the length of one move. Chunked so that samples are written as they are
     * produced: a full harvest is far too much to hold in memory. */
    (0 until config.games).grouped(config.gamesPerChunk).foreach { chunk =>
      val samples = chunk.toVector.par.map { game =>
        /* Seeded per game, not per harvest, so a run is reproducible whatever order the pool happens to finish in. */
        playGame(treeExplorer, boundaries, config, new Random(config.seed * 1000003L + game))
      }.toVector

      samples.foreach(writer.addAll)
      gamesDone += chunk.size
      onProgress(gamesDone, config.games, writer.written)
    }

  private def playGame(
      treeExplorer: TreeExplorer.MadTreeExplorer,
      boundaries: GameBoundaries,
      config: Config,
      random: Random
  ): Vector[TrainingSample] =
    given TreeExplorer.MadTreeExplorer = treeExplorer

    val openingPlies = random.nextInt(config.maxRandomOpeningPlies + 1)
    var state        = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = true)
    var ply          = 0
    var pending      = Vector.newBuilder[PendingSample]

    // The random prefix. Nothing here is recorded: the point is only to reach a position the engine has not seen.
    while ply < openingPlies && !state.ended do
      val actions = state.allValidActions
      if actions.isEmpty then ply = openingPlies
      else
        state = actions(random.nextInt(actions.length))(state)
        ply += 1

    while !state.ended && ply < config.maxPlies do
      val actions = state.allValidActions
      if actions.isEmpty then ply = config.maxPlies
      else
        val mover        = state.turnOfTeam
        val scoredMoves  = Node.MadGameStateNode(state).actionsAndScores(mover, config.minimaxDepth)
        val actionScores = new Array[Float](ActionIndex.teamSize)
        val legal        = new Array[Byte](ActionIndex.teamSize)

        /* Ties broken by taking the first maximum in `actionsAndScores` order, which is the order
         * `Node.children` produces: capturing moves first. That is the same move `Node.bestAction` would
         * settle on, and the labels are only worth anything if the game they come from is the game the
         * engine would actually have played. */
        var best      = Double.MinValue
        var bestIndex = 0
        var index     = 0
        while index < scoredMoves.length do
          val (action, score) = scoredMoves(index)
          val policyIndex     = Canonical.policyIndex(state, action)
          actionScores(policyIndex) = score.toFloat
          legal(policyIndex) = 1
          if score > best then
            best = score
            bestIndex = index
          index += 1

        /* Skipped rather than recorded wrong: on the opening plies under the special rule, canonicalising loses the
         * fact that the mover has yet to play, and with it the legality of passing. See Canonical.mirrorIsExact. */
        if Canonical.mirrorIsExact(state) then
          pending += PendingSample(StateEncoder.encode(state), actionScores, legal, best.toFloat, mover)

        val chosen =
          if random.nextDouble() < config.explorationRate then actions(random.nextInt(actions.length))
          else scoredMoves(bestIndex)._1
        state = chosen(state)
        ply += 1

    val winner = state.maybeWinner
    pending.result().map { sample =>
      val outcome = winner match
        case Some(team) if team == sample.mover => 1f
        case Some(_)                            => -1f
        case None                               => 0f
      TrainingSample(sample.features, sample.actionScores, sample.legalActions, sample.rootScore, outcome)
    }

end SupervisedHarvester
