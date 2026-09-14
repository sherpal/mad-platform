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
            println(s"Starting handling action... [value of a is ${m.aValue}]")
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

end EntryPoint
