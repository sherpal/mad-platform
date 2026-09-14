package be.doeraene.communication

import io.circe.{Decoder, Encoder}

import org.scalajs.dom.XMLHttpRequest
import scala.concurrent.Future
import scala.concurrent.Promise
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalajs.dom
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

import dom.FormData

import urldsl.language.*
import scala.scalajs.js

object makeCall:

  def downloadFile(path: String): Future[Unit] = dom.fetch(path).toFuture.map(_ => ())

  def get[Response](path: String, parameters: Map[String, String] = Map())(using Decoder[Response]): Future[Response] =
    postBody("GET", path, parameters, Option.empty[String])

  def get[Response](path: PathSegment[Unit, Any])(using Decoder[Response]): Future[Response] =
    postBody("GET", path.createPath(), Map.empty, Option.empty[String])

  def get[Q, Response](pathAndQuery: PathSegmentWithQueryParams[Unit, Any, Q, Any], q: Q)(using
      Decoder[Response]
  ): Future[Response] =
    postBody("GET", pathAndQuery.createUrlString((), q), Map.empty, Option.empty[String])

  class Poster[Response] private ():
    def apply[Body](path: String, body: Body, parameters: Map[String, String] = Map())(using
        Encoder[Body],
        Decoder[Response]
    ): Future[Response] =
      postBody("POST", path, parameters, Some(body))
    def apply(path: String)(using Decoder[Response]): Future[Response] =
      postBody("POST", path, Map(), Option.empty[String])

  private object Poster:
    def apply[Response]: Poster[Response] = new Poster

  def post[Response]: Poster[Response] = Poster[Response]

  private def postBody[Body, Response](
      method: String,
      path: String,
      parameters: Map[String, String],
      maybeBody: Option[Body]
  )(using Encoder[Body], Decoder[Response]): Future[Response] =
    rawCall[Response](method, path, parameters, maybeBody.map(_.asJson.noSpaces), "application/json")

  def postFormData[Response](path: String, formData: FormData, parameters: Map[String, String] = Map.empty)(using
      Decoder[Response]
  ): Future[Response] =
    rawCall("POST", path, parameters, Some(formData), "multipart/form-data")

  private def rawCall[Response](
      method: String,
      path: String,
      parameters: Map[String, String],
      maybeStuffToSend: Option[js.Any],
      contentType: String
  )(using Decoder[Response]): Future[Response] = {
    val request = new XMLHttpRequest

    val promise = Promise[Response]()

    request.onreadystatechange = (_: dom.Event) =>
      if request.readyState == 4 && (request.status / 200 <= 1) then {
        val body = request.response.asInstanceOf[String]

        promise.complete(decode[Response](body).toTry)
      } else if request.readyState == 4 then {
        promise.complete(
          Failure(new RuntimeException(Option(request.response.asInstanceOf[String]).getOrElse("That blew up")))
        )
      }

    val queryString = parameters.map((key, value) => s"$key=$value").mkString("&")

    request.open(
      method,
      dom.document.location.origin.toString ++ "/" ++ path ++ (if queryString.nonEmpty then "?" ++ queryString else ""),
      async = true
    )

    if contentType != "multipart/form-data" then request.setRequestHeader("Content-Type", contentType)

    maybeStuffToSend match {
      case Some(stuff) => request.send(stuff)
      case None        => request.send()
    }

    promise.future
  }
