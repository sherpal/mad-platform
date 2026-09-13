package be.doeraene.websocketcommunication

import io.circe._
import io.circe.syntax._

/** Type of message that the client websockets can send to the server. */
sealed trait ClientToServerWaitingRoom

import io.circe.generic.auto._

object ClientToServerWaitingRoom:

  case object StartWaitingForOpponent extends ClientToServerWaitingRoom
  case object StopWaitingForOpponent extends ClientToServerWaitingRoom
  case class PlayAgainst(username: String) extends ClientToServerWaitingRoom
  case object LeaveTheRoom extends ClientToServerWaitingRoom
  case class InvitationLinkPlease(guestName: String) extends ClientToServerWaitingRoom

  implicit val encoder: Encoder[ClientToServerWaitingRoom] = Encoder.instance {
    case StartWaitingForOpponent => Json.fromString(StartWaitingForOpponent.toString)
    case StopWaitingForOpponent => Json.fromString(StopWaitingForOpponent.toString)
    case LeaveTheRoom => Json.fromString(LeaveTheRoom.toString)
    case cts: PlayAgainst => cts.asJson
    case ilp: InvitationLinkPlease => ilp.asJson
  }
  implicit val decoder: Decoder[ClientToServerWaitingRoom] = List[Decoder[ClientToServerWaitingRoom]](
    valueDecoder(StartWaitingForOpponent).widen,
    valueDecoder(StopWaitingForOpponent).widen,
    valueDecoder(LeaveTheRoom).widen,
    Decoder[PlayAgainst].widen,
    Decoder[InvitationLinkPlease].widen
  ).reduceLeft(_ or _)

end ClientToServerWaitingRoom
