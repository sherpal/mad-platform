package be.doeraene.websocketcommunication

import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import scala.util.Try

/** All messages that the server can send to the clients via WebSocket. */
sealed trait ServerToClientWaitingRoom

object ServerToClientWaitingRoom:

  case object ConfigurationUpdate extends ServerToClientWaitingRoom
  case object HeartBeat extends ServerToClientWaitingRoom

  case class GameHasStarted(gameId: java.util.UUID) extends ServerToClientWaitingRoom
  case class InvitationLink(link: String) extends ServerToClientWaitingRoom



  implicit val encoder: Encoder[ServerToClientWaitingRoom] = Encoder.instance {
    case ConfigurationUpdate => Json.fromString(ConfigurationUpdate.toString)
    case HeartBeat => Json.fromString(HeartBeat.toString)
    case ghs: GameHasStarted => ghs.asJson
    case il: InvitationLink => il.asJson
  }

  implicit val decoder: Decoder[ServerToClientWaitingRoom] = List[Decoder[ServerToClientWaitingRoom]](
    valueDecoder(ConfigurationUpdate).widen,
    valueDecoder(HeartBeat).widen,
    Decoder[GameHasStarted].widen,
    Decoder[InvitationLink].widen
  ).reduceLeft(_ or _)

end ServerToClientWaitingRoom
