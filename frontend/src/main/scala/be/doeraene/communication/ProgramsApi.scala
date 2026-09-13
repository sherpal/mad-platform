package be.doeraene.communication

import org.scalajs.dom

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import be.doeraene.globals._
import be.doeraene.components.RouteDefinitions.gameId

object ProgramsApi {

  def me: Future[String] = makeCall.get[String](mePath)

  def logout: Future[Unit] = for {
    _ <- makeCall.post[String]("api/logout")
    _ <- Future {
      dom.window.location.href = "/login"
    }
  } yield ()

  def connectedPlayersInWaitingRoom: Future[List[String]] = makeCall.get[List[String]](playersInWaitingRoomPath)
  def playersWaitingToPlayCall: Future[List[String]]      = makeCall.get[List[String]](playersWaitingToPlayPath)

  def doesGameExists(id: java.util.UUID): Future[Boolean] =
    makeCall.get[java.util.UUID, Boolean](doesGameExistsPath ? gameId, id)

}
