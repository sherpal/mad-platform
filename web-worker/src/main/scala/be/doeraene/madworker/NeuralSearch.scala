package be.doeraene.madworker

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.Float32Array

import be.doeraene.mad.ai.nn.mcts.{Evaluation, SearchConfig, SearchTree}
import be.doeraene.mad.ai.nn.{ActionIndex, StateEncoder}
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState}
import be.doeraene.perf.NatArray
import be.doeraene.workers.NeuralModels

/** Runs the search in the browser, against the same `.onnx` files the JVM engine uses.
  *
  * This is the payoff for [[SearchTree]] not calling an evaluator itself. The runtime here can only be
  * asked for a result via a promise, so the loop below has to be asynchronous - but the search inside it
  * is the identical, synchronous, tested code the self-play harness runs. There is no second
  * implementation of MCTS, of the encoding, or of the rules, and so nothing that can drift between what
  * was trained and what is played.
  */
object NeuralSearch:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** One session per model, not one per worker: a player can change board without reloading the page,
    * and the models are not interchangeable.
    *
    * Memoised on the Future rather than on its result, so two requests arriving before the first load
    * finishes share it instead of each building a session - which would mean compiling 14MB of runtime
    * twice, and is the difference between a move taking half a second and five.
    */
  private var sessions: Map[String, Future[OnnxRuntime.Session]] = Map.empty

  def load(modelUrl: String, assetBase: String): Future[OnnxRuntime.Session] =
    sessions.getOrElse(
      modelUrl, {
        OnnxRuntime.configure(assetBase)
        val loading = OnnxRuntime.InferenceSession.create(modelUrl).toFuture.recoverWith { case error =>
          /* "protobuf parsing failed" means onnxruntime was handed bytes that are not a model, and it
           * cannot say why. Two causes account for nearly every occurrence, and neither is obvious from
           * the message: the file was checked out on Windows without a gitattributes marking it binary,
           * so every LF in it became CRLF; or the URL 404ed and a dev server returned index.html. Both
           * are worth naming here, because the error alone sends people to inspect the model. */
          Future.failed(
            RuntimeException(
              s"could not load the network from $modelUrl: ${error.getMessage}. " +
                "If this says protobuf parsing failed, the bytes are not a model: check the file is " +
                "exactly the size it is in the repository (a Windows checkout without .gitattributes " +
                "rewrites LF as CRLF and corrupts it), and that the URL does not 404 into index.html.",
              error
            )
          )
        }
        sessions += (modelUrl -> loading)
        loading
      }
    )

  /** Picks a move for `state`, and returns it with what the search thought the position was worth.
    *
    * The model is chosen by board, because each board has its own - see [[NeuralModels]].
    */
  def bestAction(
      state: GameState,
      simulations: Int,
      siteRoot: String,
      assetBase: String
  ): Future[(GameAction, Double)] =
    NeuralModels.modelPath(state.gameType) match
      case None =>
        /* Caught here rather than left to onnxruntime, which would report a dimension mismatch and
         * leave whoever reads it to work out that the board was the reason. Callers are expected to ask
         * NeuralModels first - see AIGameView - so reaching this is a bug, and it should say so. */
        Future.failed(
          IllegalArgumentException(
            s"no network is trained for the ${state.gameType.value} board; there are models for " +
              NeuralModels.trainedBoards.map(_.value).mkString(", ")
          )
        )

      case Some(path) =>
        load(siteRoot + path, assetBase).flatMap { ready =>
          val tree = SearchTree(state, SearchConfig(simulations = simulations, batchSize = 16))

          /* The driver loop, as a chain of futures rather than a while loop. `selectBatch` can
           * legitimately come back empty - when every leaf it reached was terminal, the rules settled
           * them and no network was needed - and that advances the search, so the right response is to
           * go round again. It cannot loop forever: an empty batch means the simulation budget was
           * consumed, so the next check is done. */
          def step(): Future[Unit] =
            if tree.isDone then Future.unit
            else
              val batch = tree.selectBatch()
              if batch.isEmpty then step()
              else
                evaluate(ready, state.gameBoundaries, batch).flatMap { evaluations =>
                  tree.submit(evaluations)
                  step()
                }

          step().map(_ => (tree.bestAction, tree.rootValue))
        }

  private def evaluate(
      session: OnnxRuntime.Session,
      boundaries: GameBoundaries,
      states: NatArray[GameState]
  ): Future[NatArray[Evaluation]] =
    val batch         = states.length
    val featureLength = StateEncoder.featureLength(boundaries)
    val features      = new Array[Float](batch * featureLength)

    var index = 0
    while index < batch do
      StateEncoder.encodeInto(states(index), features, index * featureLength)
      index += 1

    val dims  = js.Array(batch, StateEncoder.planeCount, boundaries.lastRow, boundaries.lastCol)
    val input = OnnxRuntime.Tensor("float32", Float32Array.of(features*), dims)

    session.run(js.Dictionary("board" -> input)).toFuture.map { outputs =>
      val policy     = outputs("policy").data
      val value      = outputs("value").data
      val policySize = ActionIndex.teamSize

      Array.tabulate(batch) { row =>
        val logits = new Array[Float](policySize)
        var column = 0
        while column < policySize do
          logits(column) = policy(row * policySize + column)
          column += 1
        Evaluation(logits, value(row))
      }
    }

end NeuralSearch
