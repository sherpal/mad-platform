package be.doeraene.cli

import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.Player.{minimaxMadPlayer, MadPlayer}
import be.doeraene.mad.game.{GameState, Team}
import io.circe.Codec

import java.nio.file.Paths
import scala.util.Try
import scala.jdk.CollectionConverters.*

sealed trait GameConfig

object GameConfig:

  case class PlayGameConfig(humanTeam: Option[Team], aValue: Double, minimaxDepth: Int, fromGameState: GameState)
      extends GameConfig
  case class BestActionFromFirstTurn(depth: Int)                               extends GameConfig
  case class MadTournament(minAValue: Double, maxAValue: Double, step: Double) extends GameConfig

  /** @param minimaxDepth
    *   Turns for the minimax algorithm (realistic values are 4 or 5) (less for dumb ais)
    * @param config1
    *   configuration for the "first player" (team RED) in this AI match
    * @param config2
    *   configuration for the "second player" (team BLUE) in this AI match
    */
  case class MadMatch(minimaxDepth: Int, config1: AIConfig, config2: AIConfig) extends GameConfig

  sealed trait AIConfig {
    def player(minimaxDepth: Int): MadPlayer
  }
  object AIConfig {
    given Codec[AIConfig] = io.circe.generic.semiauto.deriveCodec

    case class JPaulTheory(aValue: Double) extends AIConfig {
      def player(minimaxDepth: Int): MadPlayer = Player.jPaulTheoryPlayer(minimaxDepth, aValue)
    }
    case class Random() extends AIConfig {
      def player(minimaxDepth: Int): MadPlayer = Player.randomMadPlayer
    }
  }

  private def madMatchConfig(args: Vector[String]): GameConfig = {
    if args.isEmpty then {
      println("""
          |Usage ai-match <minimax-turn-depth> <json-for-first-config> <json-for-second-config>
          |minimax-turn-depth: int for the maximum turn depth in the minimax algo
          |Jsons for first and second config need to be valid AIConfig json serializations
          |""".stripMargin)
      throw RuntimeException("Early stop.")
    }

    if args.length < 3 then {
      throw IllegalArgumentException(s"AI match config requires 3 arguments")
    }

    val minimaxDepth = args(0).toInt
    val player1      = io.circe.parser.decode[AIConfig](args(1)).toTry.get
    val player2      = io.circe.parser.decode[AIConfig](args(2)).toTry.get

    MadMatch(minimaxDepth, config1 = player1, config2 = player2)
  }

  private def madTournamentConfig(args: Vector[String]): GameConfig = {
    if args.isEmpty then {
      println("""
          |Usage: tournament <min-a> <max-a> <step>
          |min-a: minimum value for the A parameter of the evaluator
          |max-a: maximum value
          |step: distance between two consecutive A values
          |Example: "run tournament 0 0.1 0.01"
          |""".stripMargin)
      throw RuntimeException("Early stop.")
    }

    if args.length < 3 then throw RuntimeException(s"Tournament config needs 3 arguments")

    val minA = args(0).toDouble
    val maxA = args(1).toDouble
    val step = args(2).toDouble

    MadTournament(minA, maxA, step)
  }

  private def bestActionConfig(args: Vector[String]): GameConfig = {
    if args.isEmpty then {
      println("""
          |Usage: best-action <minimax-depth>
          |minimax-depth: a Positive integer
          |Example: "run best-action 8"
          |""".stripMargin)
      throw RuntimeException("Early stop.")
    }

    BestActionFromFirstTurn(args(0).toInt)
  }

  private def playGameConfig(args: Vector[String]): GameConfig = {
    if args.isEmpty then {
      println("""
          |Usage: play <human-team> <a-value> <minimax-depth>
          |human-team: One of "red", "blue" or "random"
          |minimax-depth: a Positive integer (between 1 and 5 is reasonable)
          |a-value: a non-negative real number between 0 and 1/8
          |Example: "run play red 3"
          |""".stripMargin)
      throw RuntimeException("Early stop.")
    }

    if args.length < 3 then
      throw IllegalArgumentException(s"Config require 2 arguments. `run` without arguments.for doc.")

    val humanTeam = args(0).toLowerCase match {
      case "red"    => Some(Team.Red)
      case "blue"   => Some(Team.Blue)
      case "random" => Option.empty
      case str      => throw new IllegalArgumentException(s"First argument ")
    }

    val aValue = Try(args(1).toDouble).get

    if aValue < 0 then throw new IllegalArgumentException("A Value must be non negative")
    if aValue > 0.125 then throw new IllegalArgumentException("A Value must be smaller than 0.125")

    val minimaxDepth = Try(args(2).toInt).get

    // noinspection MapGetOrElseBoolean
    val withInitialSpecialRule: Boolean = Try(args(4)).toOption.map(_.toBoolean).getOrElse(true)

    val startingGameState = Try(args(3)).toOption match {
      case Some(filepath) =>
        (for {
          content <- Try(
            java.nio.file.Files.readAllLines(Paths.get(filepath)).asScala.toList.mkString(System.lineSeparator())
          )
          gs <- CustomGameStateParser.parse(content).toTry
        } yield gs).get
      case None => GameState.initial6By4GameState(true)
    }

    PlayGameConfig(
      humanTeam = humanTeam,
      aValue = aValue,
      minimaxDepth = minimaxDepth,
      fromGameState = startingGameState
    )
  }

  def parseCLIArgs(args: String*): GameConfig =
    if args.isEmpty then {
      println("At least one argument required, either 'play', 'best-action' or 'ai-match'")
      throw new IllegalArgumentException(s"First argument")
    } else
      args(0) match {
        case "play"        => playGameConfig(args.tail.toVector)
        case "best-action" => bestActionConfig(args.tail.toVector)
        case "tournament"  => madTournamentConfig(args.tail.toVector)
        case "ai-match"    => madMatchConfig(args.tail.toVector)
        case str => throw new IllegalArgumentException(s"First argument was $str but require 'play' or 'best-action'")
      }
