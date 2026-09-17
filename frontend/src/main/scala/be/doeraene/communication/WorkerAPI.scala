package be.doeraene.communication

import be.doeraene.workers.*
import org.scalajs.dom
import org.scalajs.dom.{Worker, WorkerOptions, WorkerType}
import scala.concurrent.{Future, Promise}
import be.doeraene.globals.webWorkerPath

import io.circe.syntax.*
import io.circe.parser.decode

import scala.reflect.ClassTag

object WorkerAPI:

  def makeWorkerCompute[T <: WorkerProtocol](message: T)(using ClassTag[message.Response]): Future[message.Response] = {
    val promise = Promise[message.Response]()

    val workerOptions = new WorkerOptions {
      `type` = WorkerType.module
    }
    val worker = Worker(webWorkerPath, workerOptions)
    worker.onerror = (event: dom.ErrorEvent) => {
      worker.terminate()
      dom.console.error(event)
      promise.failure(ProtocolException.DomErrorWrapper(event))
    }

    val toSend = (message: WorkerProtocol).asJson.noSpaces

    // The worker sends WorkerProtocol.readySignal as soon as it is ready to receive its first
    // message; see be.doeraene.EntryPoint for why we must wait for it before posting anything.
    var workerIsReady = false

    worker.onmessage = (event: dom.MessageEvent) =>
      if !workerIsReady && event.data == WorkerProtocol.readySignal then {
        workerIsReady = true
        worker.postMessage(toSend)
      } else {
        worker.terminate()
        val responseOrFailure = for {
          data <- Option(event.data).toRight(new ProtocolException.ReceivedDataWasNull)
          stringData <- data match {
            case str: String => Right(str)
            case other       => Left(new ProtocolException.ReceivedNonStringData(data))
          }
          decodedData <- decode[WorkerProtocol](stringData).swap.map(new ProtocolException.DecodingError(_)).swap
          response    <- message.collectResponse(decodedData)
        } yield response

        promise.complete(responseOrFailure.toTry)
      }

    promise.future
  }

end WorkerAPI
