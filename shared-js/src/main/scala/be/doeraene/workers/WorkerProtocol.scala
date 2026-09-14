package be.doeraene.workers

import be.doeraene.mad.game.*
import be.doeraene.utils.communication.MadTranslators.given
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import io.circe.syntax.*
import scala.reflect.ClassTag

sealed trait WorkerProtocol:
  type Response <: WorkerProtocol
  final def collectResponse(response: WorkerProtocol)(using ClassTag[Response]): Either[ProtocolException, Response] =
    maybeResponse(response).toRight(new ProtocolException.WrongReturnedValue(this, response))
  def maybeResponse(response: WorkerProtocol)(using ClassTag[Response]): Option[Response] =
    Some(response).collect { case r: Response => r }

object WorkerProtocol:

  case class CurrentGameStateWithSelectedAction(
      gameState: GameState,
      gameAction: GameAction,
      aValue: Double,
      turnAhead: Int
  ) extends WorkerProtocol:
    type Response = GameActionWithScore

  case class GameActionWithScore(
      gameAction: GameAction,
      score: Double
  ) extends WorkerProtocol:
    type Response = Nothing

  given Encoder[WorkerProtocol] = Encoder.instance {
    case element: CurrentGameStateWithSelectedAction => element.asJson
    case element: GameActionWithScore                => element.asJson
  }

  extension [T <: WorkerProtocol](decoder: Decoder[T])
    def widen: Decoder[WorkerProtocol] = decoder.map(x => x: WorkerProtocol)

  given Decoder[WorkerProtocol] =
    List[Decoder[WorkerProtocol]](
      Decoder[CurrentGameStateWithSelectedAction].widen,
      Decoder[GameActionWithScore].widen
    ).reduceLeft(_ or _)

end WorkerProtocol
