package be.doeraene.communication

import be.doeraene.workers.*
import org.scalajs.dom
import org.scalajs.dom.{Worker, WorkerOptions, WorkerType}
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
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

/** One worker, kept alive across requests.
  *
  * [[WorkerAPI.makeWorkerCompute]] spawns a worker per message and terminates it on the reply, which
  * costs nothing when the worker is pure computation - and a great deal when it is not. The neural
  * worker has to compile 14MB of onnxruntime wasm and build an inference session before it can think
  * about the position at all: measured at roughly 4.4 seconds, against about 5ms per simulation. Paying
  * that per move made a 800-simulation move take ten seconds instead of five.
  *
  * Requests are serialised rather than queued in parallel on purpose. There is one move to find at a
  * time, and a second concurrent search would only take cores away from the first.
  */
object PersistentWorker:

  private var worker: Option[Worker]                 = None
  private var ready: Option[Future[Worker]]          = None
  private var inFlight: Option[Promise[WorkerProtocol]] = None

  /** Discards the worker, so the next request builds a fresh one. Used when it has failed and cannot be
    * trusted to still be in a state where it will answer.
    */
  private def discard(): Unit =
    worker.foreach(_.terminate())
    worker = None
    ready = None
    inFlight = None

  private def ensureStarted(): Future[Worker] = ready.getOrElse {
    val promise = Promise[Worker]()
    val created = Worker(
      webWorkerPath,
      new WorkerOptions {
        `type` = WorkerType.module
      }
    )

    created.onerror = (event: dom.ErrorEvent) => {
      dom.console.error(event)
      val failure = ProtocolException.DomErrorWrapper(event)
      // Fail whatever was waiting, then start over: an errored worker will not answer.
      inFlight.foreach(_.tryFailure(failure))
      if !promise.isCompleted then promise.failure(failure)
      discard()
    }

    created.onmessage = (event: dom.MessageEvent) =>
      if !promise.isCompleted && event.data == WorkerProtocol.readySignal then promise.success(created)
      else
        val decoded = for {
          data <- Option(event.data).toRight(new ProtocolException.ReceivedDataWasNull)
          stringData <- data match {
            case str: String => Right(str)
            case other       => Left(new ProtocolException.ReceivedNonStringData(data))
          }
          message <- decode[WorkerProtocol](stringData).swap.map(new ProtocolException.DecodingError(_)).swap
        } yield message

        val waiting = inFlight
        inFlight = None
        waiting.foreach(_.complete(decoded.toTry))

    worker = Some(created)
    val started = promise.future
    ready = Some(started)
    started
  }

  def compute[T <: WorkerProtocol](message: T)(using ClassTag[message.Response]): Future[message.Response] =
    if inFlight.isDefined then
      Future.failed(new IllegalStateException("the persistent worker is already working on a request"))
    else
      ensureStarted().flatMap { started =>
        val promise = Promise[WorkerProtocol]()
        inFlight = Some(promise)
        started.postMessage((message: WorkerProtocol).asJson.noSpaces)
        promise.future.flatMap(response => Future.fromTry(message.collectResponse(response).toTry))
      }

end PersistentWorker
