package be.doeraene.models

import be.doeraene.mad.game.*
import be.doeraene.models.WithTime.time.Time
import be.doeraene.utils.communication.MadTranslators.given
import cats.kernel.Monoid
import io.circe.generic.semiauto.*
import io.circe.*

import java.time.LocalDateTime

final case class GameHistory(
    initialGameState: GameState,
    actions: Vector[WithTime[GameAction]]
):
  def allGameStates: Vector[GameState] =
    actions.scanLeft(initialGameState)((gs, action) => action.value.act(gs))

  def currentGameState: GameState = initialGameState.applyAllActions(actions.map(_.value))

  def shape: (Int, Int) = initialGameState.shape

  def gameType: GameBoundaries.GameType = initialGameState.gameType

  def withInitialSpecialRule: Boolean = initialGameState.withInitialSpecialRule

  def length: Int = actions.length + 1

  def take[N <: Int](n: N): GameHistory = copy(actions = actions.take(n - 1))

  def rewindTo[TurnNumber <: Int](turnNumber: TurnNumber): GameHistory =
    take(turnNumber + 1 - initialGameState.turnNumber)

  def add(action: GameAction, now: Time): GameHistory = copy(actions = actions :+ WithTime(action, now))

  def add(action: GameAction, startTime: LocalDateTime, now: LocalDateTime): GameHistory =
    copy(actions = actions :+ WithTime(action, Time.fromLocalDateTime(startTime, now)))

  def playersThinkingTimeInfo: PlayersThinkingTimeInfo = {
    val startingPlayer                  = initialGameState.turnOfTeam
    def playerAtActionIndex(index: Int) = if index % 2 == 0 then startingPlayer else startingPlayer.otherTeam

    def timeForTeam(team: Team): Time =
      Monoid.combineAll(
        actions
          .map(_.time)
          .zipWithIndex
          .filter((_, index) => playerAtActionIndex(index) == team)
          .map { (timeOfAction, actionIndex) =>
            val actionTeam           = playerAtActionIndex(actionIndex)
            val timeOfPreviousAction = if actionIndex == 0 then Time.zero else actions(actionIndex - 1).time
            val thinkingTime         = timeOfAction - timeOfPreviousAction
            thinkingTime
          }
      )

    PlayersThinkingTimeInfo(
      redPlayerTotal = timeForTeam(Team.Red),
      bluePlayerTotal = timeForTeam(Team.Blue),
      lastUpdate = actions.lastOption.map(_.time).getOrElse(Time.zero)
    )
  }

end GameHistory

object GameHistory:

  inline transparent def empty(
      initialGameState: GameState
  ): GameHistory =
    GameHistory(initialGameState, Vector.empty)

  given Encoder[GameHistory] = deriveCodec[GameHistory]
  given Decoder[GameHistory] = deriveCodec[GameHistory]
