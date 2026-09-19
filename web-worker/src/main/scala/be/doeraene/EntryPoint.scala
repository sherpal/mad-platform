package be.doeraene

import org.scalajs.dom.DedicatedWorkerGlobalScope.self
import org.scalajs.dom.MessageEvent

import be.doeraene.workers.WorkerProtocol
import io.circe.parser.decode
import io.circe.syntax.*

object EntryPoint:

  def main(args: Array[String]): Unit =
    self.onmessage = (messageEvent: MessageEvent) =>
      try {
        val message = decode[WorkerProtocol](messageEvent.data.asInstanceOf[String]).toTry.get

        message match {
          case m: WorkerProtocol.CurrentGameStateWithSelectedAction =>
            println(s"Starting handling action...")
            val score = be.doeraene.madworker.handleCurrentGSWithAction(
              m.gameState,
              m.gameAction,
              m.aValue,
              m.turnAhead
            )
            val response: WorkerProtocol = WorkerProtocol.GameActionWithScore(m.gameAction, score)
            self.postMessage(response.asJson.noSpaces)
          case m: WorkerProtocol.NeuralMoveRequest =>
            /* Asynchronous, unlike every other branch: the browser only offers a promise-returning way
             * to run a model. The reply is posted from the callback rather than returned, and a failure
             * has to be reported explicitly - a worker that just stopped would be indistinguishable from
             * one still thinking. */
            import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
            be.doeraene.madworker.NeuralSearch
              .bestAction(m.gameState, m.simulations, madworker.modelUrl, madworker.ortAssetBase)
              .onComplete {
                case scala.util.Success((action, value)) =>
                  val response: WorkerProtocol = WorkerProtocol.NeuralMove(action, value)
                  self.postMessage(response.asJson.noSpaces)
                case scala.util.Failure(error) =>
                  error.printStackTrace()
                  val response: WorkerProtocol = WorkerProtocol.NeuralFailure(String.valueOf(error.getMessage))
                  self.postMessage(response.asJson.noSpaces)
              }

          case other =>
            self.postMessage("bleh")
        }
      } catch {
        case t: Throwable =>
          t.printStackTrace()
          throw t
      }

    // Module workers whose entry module uses top-level `await` (as the WebAssembly-backend
    // output does, to load the .wasm file) may drop messages sent by the caller before this
    // point: the browser doesn't start dispatching queued messages until the entry module has
    // finished evaluating. Signal readiness so the caller knows it's now safe to postMessage.
    self.postMessage(WorkerProtocol.readySignal)

end EntryPoint
