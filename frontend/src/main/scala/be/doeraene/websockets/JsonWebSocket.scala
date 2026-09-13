package be.doeraene.websockets

import com.raquo.laminar.api.L._
import com.raquo.airstream.ownership.Owner
import io.circe.parser.decode
import io.circe.{Decoder, Encoder}
import org.scalajs.dom
import org.scalajs.dom.MessageEvent
import org.scalajs.dom.{Event, WebSocket}
import urldsl.language.QueryParameters.dummyErrorImpl._
import urldsl.language._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** Prepares a WebSocket to connect to the specified url. The connection actually occurs when you run the `open` method.
  *
  * Messages coming from the server can be retrieved using the `inEvents`
  * [[com.raquo.airstream.eventstream.EventStream]] and sending messages to the server can be done by writing to the
  * `outWriter` [[com.raquo.airstream.core.Observer]]
  */
final class JsonWebSocket[In, Out, P, Q] private (
    pathWithQueryParams: PathSegmentWithQueryParams[P, ?, Q, ?],
    p: P,
    q: Q,
    host: String,
    secureConnection: Boolean
)(implicit
    decoder: Decoder[In],
    encoder: Encoder[Out]
) {

  val protocol            = if secureConnection then "wss" else "ws"
  val finalHost           = if host.contains("8080") then "localhost:9000" else host
  private def url: String = protocol ++ "://" ++ finalHost ++ "/" ++ pathWithQueryParams.createUrlString(p, q)

  private lazy val socket = new WebSocket(url)

  private val inBus: EventBus[In]           = new EventBus
  private val outBus: EventBus[Out]         = new EventBus
  private val closeBus: EventBus[Unit]      = new EventBus
  private val errorBus: EventBus[dom.Event] = new EventBus
  private val openBus: EventBus[dom.Event]  = new EventBus

  private def openWebSocketConnection(using Owner) =
    for {
      _ <- Future {
        println(s"Opening websocket connection to $url")
      }
      webSocket <- Future.successful(socket)
      _ <- Future {
        webSocket.onmessage = (event: MessageEvent) =>
          decode[In](event.data.asInstanceOf[String]) match {
            case Right(in) => inBus.writer.onNext(in)
            case Left(error) =>
              dom.console.log("data", event.data)
              dom.console.error(error)
          }
      }
      _ <- Future {
        outBus.events.map(encoder.apply).map(_.noSpaces).foreach(webSocket.send)
      }
      _ <- Future { webSocket.onopen = (event: Event) => openBus.writer.onNext(event) }
      _ <- Future {
        webSocket.onerror = (event: Event) => {
          if (scala.scalajs.LinkingInfo.developmentMode) {
            dom.console.error(event)
          }
          errorBus.writer.onNext(event)
        }
      }
      _ <- Future {
        webSocket.onclose = (_: Event) => closeBus.writer.onNext(())
      }
    } yield ()

  def open()(using Owner): Unit = openWebSocketConnection onComplete {
    case scala.util.Success(_)   => println("WebSocket connection opened")
    case scala.util.Failure(exc) => throw exc
  }
  // zio.Runtime.default.unsafeRunToFuture(openWebSocketConnection)

  def close(): Unit = {
    socket.close()
    closeBus.writer.onNext(())
  }

  val inEvents: EventStream[In]       = inBus.events
  val outWriter: WriteBus[Out]        = outBus.writer
  val closedEvents: EventStream[Unit] = closeBus.events
  val errorEvents: EventStream[Event] = errorBus.events
  val openEvents: EventStream[Event]  = openBus.events

}

object JsonWebSocket {

  def apply[In, Out](
      path: PathSegment[Unit, ?],
      host: String = dom.document.location.host,
      secureConnection: Boolean = dom.document.location.protocol.contains("https")
  )(using
      Decoder[In],
      Encoder[Out]
  ): JsonWebSocket[In, Out, Unit, Unit] = new JsonWebSocket(path ? ignore, (), (), host, secureConnection)

  def apply[In, Out, Q](
      path: PathSegment[Unit, ?],
      query: QueryParameters[Q, ?],
      q: Q,
      host: String
  )(using Decoder[In], Encoder[Out]): JsonWebSocket[In, Out, Unit, Q] =
    new JsonWebSocket(path ? query, (), q, host, dom.document.location.protocol.contains("https"))

  def apply[In, Out, Q](
      path: PathSegment[Unit, ?],
      query: QueryParameters[Q, ?],
      q: Q
  )(using Decoder[In], Encoder[Out]): JsonWebSocket[In, Out, Unit, Q] =
    apply(path, query, q, dom.document.location.host)

}
