package be.doeraene.communication

import be.doeraene.mad.game.*

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.Success
import scala.scalajs.js
import scala.util.Random

import be.doeraene.utils.communication.MadTranslators.given
import io.circe.generic.auto.*
import be.doeraene.communication.WorkerAPI.makeWorkerCompute
import be.doeraene.workers.WorkerProtocol.{
  CurrentGameStateWithSelectedAction,
  GameActionWithScore,
  NeuralMove,
  NeuralMoveRequest
}
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

object AIApi:

  def askNextAction(
      turnAhead: Int,
      aValue: Double,
      currentGameState: GameState,
      updateProgress: Int => Unit
  ): Future[GameAction] = {
    val possibleActions = currentGameState.allValidActions

    println("Asking workers to compute best actions...")
    println("Starting time: " ++ (new js.Date).toString)

    val possibleActionsCount = possibleActions.length
    var currentActionsDone   = 0
    updateProgress(0)

    for {
      actionsWithScores <- futureParN(4)(possibleActions.toVector)(action =>
        makeWorkerCompute(
          CurrentGameStateWithSelectedAction(
            currentGameState,
            action,
            aValue,
            turnAhead
          )
        ).andThen { case scala.util.Success(value) =>
          currentActionsDone += 1
          updateProgress(currentActionsDone * 100 / possibleActionsCount)
          println(s"Action ${value.gameAction.prettyPrint(currentGameState)} leads to ${value.score}.")
        }
      )
      actionsByScore = actionsWithScores.sortBy(-_.score)
      selectedAction = (if currentGameState.turnNumber > 2 then actionsByScore
                        else Random.shuffle(actionsByScore.take(5))).head
      _ = println("End: " ++ (new js.Date).toString)
    } yield selectedAction.gameAction

  }

  /** Asks the neural engine for a move.
    *
    * One worker round-trip for the whole move, unlike [[askNextAction]], which spawns a worker per
    * candidate move and scores each independently. A tree search decides for itself where to spend its
    * next simulation, so it cannot be split up that way - and it does not need to be, since the network
    * evaluates a position in well under a millisecond.
    *
    * @param simulations
    *   how many leaves the search visits. Strength against how long the move takes; a few hundred is
    *   quick, a few thousand is stronger and noticeably slower in a browser.
    */
  def askNeuralAction(currentGameState: GameState, simulations: Int): Future[GameAction] =
    println(s"Asking the neural worker for a move ($simulations simulations)...")
    val started = new js.Date

    PersistentWorker.compute(NeuralMoveRequest(currentGameState, simulations)).map { move =>
      val elapsed = (new js.Date).getTime() - started.getTime()
      println(
        f"Neural move ${move.gameAction.prettyPrint(currentGameState)}, " +
          f"position worth ${move.value}%.2f to the mover (${elapsed}%.0f ms)"
      )
      move.gameAction
    }

  def askNextActionViaServer(turnAhead: Int, aValue: Double, currentGameState: GameState): Future[GameAction] =
    makeCall
      .post[GameAction](
        "api/minimax",
        currentGameState,
        Map(
          "a-value"    -> aValue.toString,
          "turn-ahead" -> turnAhead.toString,
          "num-row"    -> currentGameState.shape._1.toString,
          "num-col"    -> currentGameState.shape._2.toString
        )
      )
      .andThen { case Success(gameAction) =>
        println(gameAction.prettyPrint(currentGameState))
      }

  private def futureParN[A, B](n: Int)(s: Seq[A])(f: A => Future[B])(using ExecutionContext): Future[Seq[B]] =
    if s.isEmpty
    then Future.successful(Seq.empty[B])
    else {
      val numElements                                   = s.length
      val resultRef: AtomicReference[(Array[Any], Int)] = new AtomicReference((new Array[Any](numElements), 0))
      val promise                                       = Promise[Seq[B]]()
      val failedRef                                     = new AtomicBoolean(false)

      val lastIndex: AtomicInteger = new AtomicInteger(-1)

      def launchNextFuture(): Unit = {
        val index = lastIndex.incrementAndGet()
        if index < numElements then {
          val nextFuture = f(s(index))
          nextFuture.onComplete {
            case scala.util.Failure(exc) =>
              if failedRef.getAndSet(true) then {
                promise.failure(exc)
              }
            case scala.util.Success(b) =>
              val (result, completed) = resultRef.updateAndGet { case (rs, currentlyCompleted) =>
                rs(index) = b
                (rs, currentlyCompleted + 1)
              }
              if completed == numElements then promise.success(result.asInstanceOf[Array[B]].toSeq)
              else {
                launchNextFuture()
              }
          }
        }
      }

      val parallelism = if n > 0 then n else numElements
      for (_ <- 1 to parallelism)
        launchNextFuture()

      promise.future
    }
