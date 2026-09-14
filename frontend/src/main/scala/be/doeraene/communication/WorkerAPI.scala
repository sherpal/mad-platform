package be.doeraene.communication

import be.doeraene.workers.*
import org.scalajs.dom
import org.scalajs.dom.Worker
import scala.concurrent.{Future, Promise}
import be.doeraene.globals.webWorkerPath

import io.circe.syntax.*
import io.circe.parser.decode

import scala.reflect.ClassTag

object WorkerAPI:

  def makeWorkerCompute[T <: WorkerProtocol](message: T)(using ClassTag[message.Response]): Future[message.Response] = {
    val promise = Promise[message.Response]()

    val worker = Worker(webWorkerPath)
    worker.onerror = (event: dom.ErrorEvent) => {
      worker.terminate()
      dom.console.error(event)
      promise.failure(ProtocolException.DomErrorWrapper(event))
    }

    worker.onmessage = (event: dom.MessageEvent) => {
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
    val toSend = (message: WorkerProtocol).asJson.noSpaces
    worker.postMessage(toSend)

    promise.future
  }

end WorkerAPI
