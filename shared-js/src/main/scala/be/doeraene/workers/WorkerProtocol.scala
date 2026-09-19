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

  /** Sent by the worker, outside the regular request/response protocol, as soon as it has
    * finished loading and is ready to receive its first message. See [[be.doeraene.EntryPoint]]
    * for why this handshake is needed.
    */
  val readySignal: String = "worker-ready"

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

  /** Asks the worker to pick a move with the neural network and its search.
    *
    * One message for a whole move, unlike [[CurrentGameStateWithSelectedAction]], which the caller sends
    * once per candidate move and scores independently. A tree search cannot be split that way - it
    * decides for itself which branches deserve the next simulation, and that is the whole point of it.
    *
    * @param simulations
    *   how many leaves the search visits. The strength dial, and the one thing the caller can trade
    *   against how long a move takes.
    */
  case class NeuralMoveRequest(
      gameState: GameState,
      simulations: Int
  ) extends WorkerProtocol:
    type Response = NeuralMove

  /** @param value
    *   what the search thinks the position is worth for the player to move, in -1 to 1. Worth returning
    *   even though nothing needs it yet: it is the natural thing to show in a UI, and the caller cannot
    *   recompute it without redoing the search.
    */
  case class NeuralMove(
      gameAction: GameAction,
      value: Double
  ) extends WorkerProtocol:
    type Response = Nothing

  /** Sent instead of a [[NeuralMove]] when the worker could not load or run the model. A worker that
    * failed silently would look exactly like one still thinking.
    */
  case class NeuralFailure(reason: String) extends WorkerProtocol:
    type Response = Nothing

  given Encoder[WorkerProtocol] = Encoder.instance {
    case element: CurrentGameStateWithSelectedAction => element.asJson
    case element: GameActionWithScore                => element.asJson
    case element: NeuralMoveRequest                  => element.asJson
    case element: NeuralMove                         => element.asJson
    case element: NeuralFailure                      => element.asJson
  }

  extension [T <: WorkerProtocol](decoder: Decoder[T])
    def widen: Decoder[WorkerProtocol] = decoder.map(x => x: WorkerProtocol)

  given Decoder[WorkerProtocol] =
    List[Decoder[WorkerProtocol]](
      Decoder[CurrentGameStateWithSelectedAction].widen,
      Decoder[GameActionWithScore].widen,
      Decoder[NeuralMoveRequest].widen,
      Decoder[NeuralMove].widen,
      Decoder[NeuralFailure].widen
    ).reduceLeft(_ or _)

end WorkerProtocol
