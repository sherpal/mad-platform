package be.doeraene.models

import be.doeraene.mad.game._
import be.doeraene.utils.communication.MadTranslators.given
import io.circe.generic.semiauto._
import io.circe._
import scala.compiletime.ops.int.>

final case class GameHistory(
    initialGameState: GameState,
    actions: List[GameAction]
):
  def allGameStates: List[GameState] =
    actions.scanLeft(initialGameState)((gs, action) => action.act(gs))
  def currentGameState: GameState = initialGameState.applyAllActions(actions)

  def shape: (Int, Int) = initialGameState.shape

  def gameType: GameBoundaries.GameType = initialGameState.gameType

  def withInitialSpecialRule: Boolean = initialGameState.withInitialSpecialRule

  def length: Int = actions.length + 1

  def take[N <: Int](n: N): GameHistory = copy(actions = actions.take(n - 1))

  def rewindTo[TurnNumber <: Int](turnNumber: TurnNumber): GameHistory =
    take(turnNumber + 1 - initialGameState.turnNumber)

object GameHistory:

  inline transparent def empty(
      initialGameState: GameState
  ): GameHistory =
    GameHistory(initialGameState, Nil)

  given Encoder[GameHistory] = deriveCodec[GameHistory]
  given Decoder[GameHistory] = deriveCodec[GameHistory]
