package be.doeraene.mad.game

import be.doeraene.mad.ai.minimax.Node.MadGameStateNode
import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.minimax.{Node, TreeExplorer}

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.duration.*

class GameStateDisplay extends munit.FunSuite:
  test("showInitialGameState") {
    println(GameState.initial6By4GameState(true).prettyPrint)
  }

  def timeIt[A](effect: => A): (A, FiniteDuration) =
    val startTime = LocalDateTime.now()
    val a         = effect
    val endTime   = LocalDateTime.now()
    val timeTaken = startTime.until(endTime, ChronoUnit.MILLIS)
    (a, timeTaken.millis)

  def minimaxVSMinimax(): Unit = {
    val evaluator = Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(0.03))
    given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(evaluator)
    val player                                            = Player.minimaxMadPlayer(2)

    Player.playMadGame(player, player, GameState.initial6By4GameState(true))
    ()
  }

  def randomVSMinimax(): Unit = {

    val evaluator = Node.evaluatorFromPieceEvaluator(PieceEvaluator.jPaulDoeFirstTheory(0.03))
    given TreeExplorer[GameState, GameAction, Team] = TreeExplorer.MadGameStateTreeExplorer(evaluator)
    val bluePlayer                                        = Player.randomMadPlayer
    val redPlayer                                         = Player.minimaxMadPlayer(2)

    val gameHistory = Player.playMadGame(redPlayer, bluePlayer, GameState.initial6By4GameState(true))

    assertEquals(Option[Team](Team.Red), gameHistory.last.maybeWinner)
  }

end GameStateDisplay
