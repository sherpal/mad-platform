package be.doeraene.madworker

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.Float32Array

import be.doeraene.mad.ai.nn.mcts.{Evaluation, SearchConfig, SearchTree}
import be.doeraene.mad.ai.nn.{ActionIndex, StateEncoder}
import be.doeraene.mad.game.{GameAction, GameBoundaries, GameState}
import be.doeraene.perf.NatArray

/** Runs the search in the browser, against the same `.onnx` the JVM engine uses.
  *
  * This is the payoff for [[SearchTree]] not calling an evaluator itself. The runtime here can only be
  * asked for a result via a promise, so the loop below has to be asynchronous - but the search inside it
  * is the identical, synchronous, tested code the self-play harness runs. There is no second
  * implementation of MCTS, of the encoding, or of the rules, and so nothing that can drift between what
  * was trained and what is played.
  */
object NeuralSearch:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private var session: Option[Future[OnnxRuntime.Session]] = None

  /** Loads the model, once per worker.
    *
    * Memoised on the Future rather than on its result, so two requests arriving before the first load
    * finishes share it instead of each building a session.
    */
  def load(modelUrl: String, assetBase: String): Future[OnnxRuntime.Session] =
    session.getOrElse {
      OnnxRuntime.configure(assetBase)
      val loading = OnnxRuntime.InferenceSession.create(modelUrl).toFuture.recoverWith { case error =>
        /* "protobuf parsing failed" means onnxruntime was handed bytes that are not a model, and it
         * cannot say why. Two causes account for nearly every occurrence, and neither is obvious from
         * the message: the file was checked out on Windows without a gitattributes marking it binary,
         * so every LF in it became CRLF; or the URL 404ed and a dev server returned index.html. Both
         * are worth naming here, because the error alone sends people looking at the model. */
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
      session = Some(loading)
      loading
    }

  /** Picks a move for `state`, and returns it with what the search thought the position was worth. */
  /** The board the shipped network was trained for.
    *
    * Not a configuration option: the model's input is 19 x rows x cols and its heads end in a Linear
    * over rows * cols cells, so the board size is baked into the weights. Another size is not a harder
    * position, it is a tensor onnxruntime refuses.
    */
  val trainedFor: GameBoundaries.GameType = GameBoundaries._6by4

  def bestAction(
      state: GameState,
      simulations: Int,
      modelUrl: String,
      assetBase: String
  ): Future[(GameAction, Double)] =
    if state.gameType != trainedFor then
      /* Caught here rather than left to onnxruntime, which would report a dimension mismatch and leave
       * whoever reads it to work out that the board is the reason. Callers are expected to check first -
       * see AIGameView - so reaching this is a bug, and it should say so. */
      Future.failed(
        IllegalArgumentException(
          s"the network was trained for the ${trainedFor.value} board and cannot play ${state.gameType.value}"
        )
      )
    else load(modelUrl, assetBase).flatMap { ready =>
      val config = SearchConfig(simulations = simulations, batchSize = 16)
      val tree   = SearchTree(state, config)

      /* The driver loop, as a chain of futures rather than a while loop. `selectBatch` can legitimately
       * come back empty - when every leaf it reached was terminal, the rules settled them and no network
       * was needed - and that advances the search, so the right response is to go round again. It cannot
       * loop forever: an empty batch means the simulation budget was consumed, so the next check is done. */
      def step(): Future[Unit] =
        if tree.isDone then Future.unit
        else
          val batch = tree.selectBatch()
          if batch.isEmpty then step()
          else evaluate(ready, state.gameBoundaries, batch).flatMap { evaluations =>
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

    val dims = js.Array(batch, StateEncoder.planeCount, boundaries.lastRow, boundaries.lastCol)
    val input = OnnxRuntime.Tensor("float32", Float32Array.of(features*), dims)

    session.run(js.Dictionary("board" -> input)).toFuture.map { outputs =>
      val policy = outputs("policy").data
      val value  = outputs("value").data
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
