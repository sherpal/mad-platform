package be.doeraene.websocketcommunication

import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.mad.game._

trait ClientToServerPlayingRoom

object ClientToServerPlayingRoom:

  case class PlayAction(action: GameAction) extends ClientToServerPlayingRoom

  implicit val encoder: Encoder[ClientToServerPlayingRoom] = Encoder.instance {
    case pa: PlayAction => pa.asJson
  }

  implicit val decoder: Decoder[ClientToServerPlayingRoom] = List[Decoder[ClientToServerPlayingRoom]](
    Decoder[PlayAction].widen
  ).reduceLeft(_ or _)

end ClientToServerPlayingRoom

