package be.doeraene

import be.doeraene.cli.{AskForGameAction, GameConfig}
import be.doeraene.mad.ai.Player.{minimaxMadPlayer, MadPlayer}
import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}
import be.doeraene.mad.ai.tuning.ClaudeWeightTuner
import be.doeraene.mad.ai.{tournament, Player}
import be.doeraene.mad.game.{GameAction, GameState, PieceEvaluator, Team}

import java.nio.file.Paths
import java.time.ZoneOffset
import scala.util.Random
import scala.jdk.CollectionConverters.*

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

  }
