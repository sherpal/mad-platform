package be.doeraene

import be.doeraene.cli.{AskForGameAction, GameConfig}
import be.doeraene.mad.ai.Player.{MadPlayer, minimaxMadPlayer}
import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}
import be.doeraene.mad.ai.{Player, TacticalWeights, benchmark, tournament}
import be.doeraene.mad.ai.tuning.{ClaudeWeightTuner, TacticalWeightTuner, TexelTuner}
import be.doeraene.mad.ai.{benchmark, tournament, Player, TacticalWeights}
import be.doeraene.mad.ai.nn.OnnxEvaluator
import be.doeraene.mad.ai.nn.data.{ShardWriter, SupervisedHarvester}
import be.doeraene.mad.ai.nn.mcts
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState, PieceEvaluator, Team}

import java.nio.file.Paths
import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*
import scala.util.Random

@main def run(args: String*): Unit =
  GameConfig.parseCLIArgs(args*) match {
    case config: GameConfig.PlayGameConfig =>
      // given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(
      //   /* Switch the comments in the two following lines to try a different evaluator. */
      //   Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(config.aValue))
      //   //Node.madGameStateEvaluator
      // )
      val humanTeam = config.humanTeam.getOrElse(if Random.nextBoolean() then Team.Red else Team.Blue)

      val humanPlayer = Player(Player.Name("Human"), AskForGameAction.askForOutputFrom)
      val aiPlayer = // Player.minimaxMadPlayer(config.minimaxDepth)
        Player.jPaulTheoryPlayer(config.minimaxDepth, config.aValue)

      val redPlayer  = if humanTeam == Team.Red then humanPlayer else aiPlayer
      val bluePlayer = if humanTeam == Team.Red then aiPlayer else humanPlayer

      Player.playMadGame(redPlayer, bluePlayer, config.fromGameState)
    case config: GameConfig.BestActionFromFirstTurn =>
      given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(
        /* Switch the comments in the two following lines to try a different evaluator. */
        Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(0))
        // Node.madGameStateEvaluator
      )
      val action = Node.MadGameStateNode(GameState.initial6By4GameState(true)).bestAction(Team.Red, config.depth)
      println(s"Best action is ${action.prettyPrint(GameState.initial6By4GameState(true))}")
    case config: GameConfig.MadTournament =>
      val GameConfig.MadTournament(minA, maxA, step) = config
      val aValues = LazyList.continually(step).scanLeft(minA)(_ + _).takeWhile(_ <= maxA).toList

      val aiPlayers = for {
        a         <- aValues
        turnAhead <- (1 to 5).toList
        player = Player.minimaxMadPlayer(turnAhead)(using
          TreeExplorer.MadGameStateTreeExplorer(
            Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(a))
          )
        )
      } yield player.withName(Player.Name(s"Minimax-$a-$turnAhead"))

      val players = Player.randomMadPlayer :: aiPlayers

      val tournamentResults = tournament.playMadTournament(players, GameState.initial6By4GameState(true))
      tournamentResults.foreach(println)
      java.nio.file.Files.write(
        Paths.get("./data/tournament-results/results" + java.time.LocalDateTime.now.toEpochSecond(ZoneOffset.UTC)),
        (tournament.MatchResult.csvHeader +: tournamentResults.map(_.toCSV)).asJava
      )
    case GameConfig.MadMatch(minimaxDepth, config1, config2) =>
      val player1 = config1.player(minimaxDepth)
      val player2 = config2.player(minimaxDepth)

      val result = Player.playMadGame(player1, player2, GameState.initial6By4GameState(true), verbose = false)

      val endingGameState = result.last
      val maybeWinner     = endingGameState.maybeWinner
      println(s"Game ended in ${endingGameState.turnNumber} turns")
      println(maybeWinner match {
        case Some(team) => s"Winner is $team"
        case None       => "It's a tie!"
      })

    case GameConfig.MadBenchmark(minimaxDepth, config1, config2, openings, seed, skip, opponentDepth) =>
      val games          = benchmark.Benchmark.battery(openings, seed, skip)
      val depthOfOpponent = opponentDepth.getOrElse(minimaxDepth)
      println(
        s"Benchmark: $config1 at depth $minimaxDepth vs $config2 at depth $depthOfOpponent " +
          s"over ${games.size} games"
      )

      val (report, time) = Player.timeIt(
        benchmark.Benchmark.run(
          config1.player(minimaxDepth),
          config2.player(depthOfOpponent),
          games,
          (done, total) => if done % 10 == 0 || done == total then println(s"  $done/$total games played")
        )
      )

      println(report.pretty(s"$config1@$minimaxDepth", s"$config2@$depthOfOpponent"))
      println(s"(took ${time.toSeconds}s)")

    case GameConfig.TexelTune(openings, gameDepth, passes, opponentConfig) =>
      val games    = benchmark.Benchmark.battery(openings, seed = 42L)
      val opponent = opponentConfig.player(gameDepth)
      println(s"Texel tuning: harvesting positions from ${games.size} depth-$gameDepth games vs $opponentConfig")

      val (samples, harvestTime) = Player.timeIt(
        TexelTuner.collectSamples(Player.tacticalPlayer(gameDepth), opponent, games)
      )
      /* Split by position order, not at random. Samples arrive grouped by game, so a contiguous cut keeps whole
       * games on one side or the other; a random split would scatter near-identical consecutive positions of the
       * same game across both sides and make the held-out loss meaninglessly optimistic. */
      val (training, heldOut) = samples.splitAt(samples.length * 3 / 4)
      println(s"Harvested ${samples.length} positions in ${harvestTime.toSeconds}s " +
        s"(${training.length} training, ${heldOut.length} held out)")

      val (fitted, scale) = TexelTuner.fit(training.toArray, heldOut.toArray, passes = passes) { sweep =>
        println(
          f"[pass ${sweep.pass}%2d] ${sweep.parameter}%-24s ${sweep.from}%9.4f -> ${sweep.to}%9.4f  " +
            f"loss=${sweep.trainingLoss}%.6f"
        )
      }

      println("=" * 70)
      println(s"Fitted weights: $fitted")
      println(f"Logistic scale: $scale%.3f")

      val resultsDir = Paths.get("./data/tuning-results")
      java.nio.file.Files.createDirectories(resultsDir)
      val resultFile =
        resultsDir.resolve(s"texel-weights-${java.time.LocalDateTime.now.toEpochSecond(ZoneOffset.UTC)}.txt")
      java.nio.file.Files.writeString(resultFile, s"scale=$scale\n$fitted\n")
      println(s"Saved to $resultFile")

    case GameConfig.TuneTactical(iterations, minimaxDepth, openings, seed, opponentConfig) =>
      val training   = benchmark.Benchmark.battery(openings, seed)
      val validation = benchmark.Benchmark.battery(openings, seed, skip = openings)
      val opponent   = opponentConfig.player(minimaxDepth)

      println(
        s"Tuning TacticalWeights: $iterations rounds, depth $minimaxDepth, vs $opponentConfig, " +
          s"${training.size} training games (${validation.size} held out for validation)"
      )

      val (best, bestScore) = TacticalWeightTuner.hillClimb(iterations, minimaxDepth, opponent, training) { step =>
        val mark = if step.accepted then "accepted" else "rejected"
        println(
          f"[${step.iteration}%3d/$iterations] $mark%-8s ${step.fieldsTried.mkString(", ")}%-45s " +
            f"tried=${step.triedScore}%.1f  best=${step.currentBestScore}%.1f/${training.size}%d"
        )
      }

      val defaultTraining = TacticalWeightTuner.score(TacticalWeights.default, minimaxDepth, opponent, training)
      val defaultHeldOut  = TacticalWeightTuner.score(TacticalWeights.default, minimaxDepth, opponent, validation)
      val tunedHeldOut    = TacticalWeightTuner.score(best, minimaxDepth, opponent, validation)

      println("=" * 70)
      println(f"training  : default $defaultTraining%.1f -> tuned $bestScore%.1f  / ${training.size}%d")
      println(f"held out  : default $defaultHeldOut%.1f -> tuned $tunedHeldOut%.1f  / ${validation.size}%d")
      println(s"Best weights: $best")

      val resultsDir = Paths.get("./data/tuning-results")
      java.nio.file.Files.createDirectories(resultsDir)
      val resultFile =
        resultsDir.resolve(s"tactical-weights-${java.time.LocalDateTime.now.toEpochSecond(ZoneOffset.UTC)}.txt")
      java.nio.file.Files.writeString(
        resultFile,
        s"training=$bestScore/${training.size} (default $defaultTraining)\n" +
          s"heldOut=$tunedHeldOut/${validation.size} (default $defaultHeldOut)\n$best\n"
      )
      println(s"Saved to $resultFile")

    case GameConfig.TuneClaude(iterations, minimaxDepth) =>
      val batterySize = ClaudeWeightTuner.defaultBattery.size
      println(s"Tuning ClaudeWeights: $iterations rounds, depth $minimaxDepth, battery of $batterySize games")

      val (bestWeights, bestScore) = ClaudeWeightTuner.hillClimb(iterations, minimaxDepth) { step =>
        val mark = if step.accepted then "accepted" else "rejected"
        println(
          f"[${step.iteration}%3d/$iterations] $mark%-8s " +
            f"${step.fieldTried}%-24s ${step.oldValue}%8.3f -> ${step.triedValue}%8.3f  " +
            f"tried=${step.triedScore}%.1f/$batterySize  best=${step.currentBestScore}%.1f/$batterySize"
        )
      }

      println("=" * 70)
      println(s"Best score: $bestScore / $batterySize")
      println(s"Best weights: $bestWeights")

      val resultsDir = Paths.get("./data/tuning-results")
      java.nio.file.Files.createDirectories(resultsDir)
      val resultFile =
        resultsDir.resolve(s"claude-weights-${java.time.LocalDateTime.now.toEpochSecond(ZoneOffset.UTC)}.txt")
      java.nio.file.Files.writeString(resultFile, s"score=$bestScore/$batterySize\n$bestWeights\n")
      println(s"Saved to $resultFile")

    case GameConfig.HarvestPositions(output, games, minimaxDepth, seed, explorationRate, samplesPerShard) =>
      val boundaries = GameBoundaries.originalSixByFour
      val outputDir  = Paths.get(output)
      val harvestConfig = SupervisedHarvester.Config(
        games = games,
        seed = seed,
        minimaxDepth = minimaxDepth,
        explorationRate = explorationRate
      )

      println(
        s"Harvesting positions from $games depth-$minimaxDepth self-play games " +
          f"(exploration $explorationRate%.2f, seed $seed) into $outputDir"
      )

      val writer = ShardWriter(outputDir, boundaries, samplesPerShard)
      val (_, time) = Player.timeIt(
        SupervisedHarvester.harvest(
          Player.tacticalTreeExplorer,
          boundaries,
          harvestConfig,
          writer,
          (done, total, samples) => println(f"  $done%d/$total%d games, $samples%d positions")
        )
      )
      writer.close()

      println(s"Wrote ${writer.written} positions to $outputDir in ${time.toSeconds}s")

    case GameConfig.NeuralBenchmark(model, simulations, batchSize, minimaxDepth, opponentConfig, openings, seed) =>
      val games      = benchmark.Benchmark.battery(openings, seed)
      val opponent   = opponentConfig.player(minimaxDepth)
      val searchConf = mcts.SearchConfig(simulations = simulations, batchSize = batchSize)

      /* The literal "uninformed" runs the same search with flat priors and no value estimate, which is
       * the baseline the network has to beat to be worth anything. Without it, a network that had learnt
       * nothing and a network that had learnt plenty would both just look like "MCTS scores X". */
      val evaluator =
        if model == "uninformed" then mcts.BatchEvaluator.uninformed
        else OnnxEvaluator(Paths.get(model), GameBoundaries.originalSixByFour)
      try
        val label     = if model == "uninformed" then "UCT" else "Neural"
        val candidate = mcts.Mcts.player(evaluator, searchConf, name = label)
        println(
          s"Benchmark: $simulations-simulation MCTS over $model vs $opponentConfig at depth $minimaxDepth, " +
            s"over ${games.size} games"
        )

        val (report, time) = Player.timeIt(
          benchmark.Benchmark.run(
            candidate,
            opponent,
            games,
            (done, total) => if done % 10 == 0 || done == total then println(s"  $done/$total games played")
          )
        )

        println(report.pretty(s"$label-$simulations", opponentConfig.toString))
        println(s"(took ${time.toSeconds}s)")
      finally
        evaluator match
          case closeable: AutoCloseable => closeable.close()
          case _                        => ()

  }
