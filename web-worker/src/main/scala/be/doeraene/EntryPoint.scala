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
