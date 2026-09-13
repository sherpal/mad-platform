package be.doeraene.websocketcommunication

import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.mad.game._
import be.doeraene.models._

import scala.util.Try
import scala.concurrent.duration._

trait ServerToClientPlayingRoom

object ServerToClientPlayingRoom:

  case object HeartBeat extends ServerToClientPlayingRoom
  case class GameStateWrapper(allGameInfo: AllGameInfo) extends ServerToClientPlayingRoom

  /** Sent when a player sent a wrong action. */
  case class IllegalAction(gameState: GameState, action: GameAction) extends ServerToClientPlayingRoom
  case class ThisIsYourTeam(team: Team) extends ServerToClientPlayingRoom

  implicit val encoder: Encoder[ServerToClientPlayingRoom] = Encoder.instance {
    case HeartBeat                    => Json.fromString(HeartBeat.toString)
    case gsWrapper: GameStateWrapper  => gsWrapper.asJson
    case illegalAction: IllegalAction => illegalAction.asJson
    case tiyt: ThisIsYourTeam         => tiyt.asJson
  }

  implicit val decoder: Decoder[ServerToClientPlayingRoom] = List[Decoder[ServerToClientPlayingRoom]](
    valueDecoder(HeartBeat).widen,
    Decoder[GameStateWrapper].widen,
    Decoder[IllegalAction].widen,
    Decoder[ThisIsYourTeam].widen
  ).reduceLeft(_ or _)

end ServerToClientPlayingRoom
