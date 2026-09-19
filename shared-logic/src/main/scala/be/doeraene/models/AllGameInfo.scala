package be.doeraene.models

import be.doeraene.mad.game.*
import io.circe.{Codec, Decoder, Encoder}

import java.time.*
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/** [[AllGameInfo]] remembers the complete information about the game, with some player information that are not
  * directly encoded into the [[GameState]] (because somewhat unrelated to MAD itself.)
  */
final case class AllGameInfo(
    redInfo: AllGameInfo.PlayerInfo,
    blueInfo: AllGameInfo.PlayerInfo,
    gameHistory: GameHistory,
    startTime: LocalDateTime,
    lastUpdateTime: LocalDateTime
):
  def shape: (Int, Int) = gameHistory.shape

  inline transparent def addAction(action: GameAction, now: LocalDateTime): AllGameInfo =
    val newHistory          = gameHistory.add(action, startTime, now)
    val newGameState        = newHistory.currentGameState
    val justPlayed          = newGameState.turnOfTeam.otherTeam
    val timeSinceLastUpdate = lastUpdateTime.until(now, temporal.ChronoUnit.SECONDS).seconds
    val newRedInfo          = if justPlayed == Team.Red then redInfo.addTime(timeSinceLastUpdate) else redInfo
    val newBlueInfo         = if justPlayed == Team.Blue then blueInfo.addTime(timeSinceLastUpdate) else blueInfo
    AllGameInfo(
      newRedInfo,
      newBlueInfo,
      newHistory,
      startTime,
      now
    )

object AllGameInfo:
  private given Codec[FiniteDuration] = Codec.from(
    Decoder.decodeString.emapTry[FiniteDuration] { s =>
      for {
        d <- Try(scala.concurrent.duration.Duration(s))
        _ <-
          if !d.isFinite then Failure(IllegalArgumentException(s"Finite duration must be finite, got $s"))
          else Success(())
      } yield FiniteDuration(d.length, d.unit)
    },
    Encoder.encodeString.contramap[FiniteDuration] { d =>
      s"${d.length} ${d.unit}"
    }
  )

  case class PlayerInfo(name: PlayerName.HumanPlayerName, totalThinkingTime: FiniteDuration) derives Codec:
    def addTime(duration: FiniteDuration): PlayerInfo = copy(totalThinkingTime = totalThinkingTime + duration)

//  def initial(
//      initialGameState: GameState,
//      initialActions: List[GameAction],
//      redPlayerName: PlayerName.HumanPlayerName,
//      bluePlayerName: PlayerName.HumanPlayerName,
//      startTime: LocalDateTime
//  ): AllGameInfo = AllGameInfo(
//    PlayerInfo(redPlayerName, 0.second),
//    PlayerInfo(bluePlayerName, 0.second),
//    GameHistory(initialGameState, initialActions),
//    startTime,
//    startTime
//  )
