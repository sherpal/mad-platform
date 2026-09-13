package be.doeraene.utils.communication

import be.doeraene.mad.game._
import be.doeraene.mad.game.GamePiece._
import be.doeraene.mad.game.Positions._
import io.circe.parser.{decode => circeDecode}
import Translator.{Json, JsonTranslator}
import be.doeraene.models._
import java.time.LocalDateTime

import io.circe.generic.semiauto._
import io.circe.{Codec => CirceCodec, Decoder => CirceDecoder, Encoder => CirceEncoder, KeyEncoder}

import scala.util.Try
import scala.concurrent.duration.FiniteDuration

object MadTranslators:

  given gamePieceCirceEncoder: CirceEncoder[GamePiece] =
    CirceEncoder[String].contramap(_.prettyPrint)
  given gamePieceCirceDecoder: CirceDecoder[GamePiece] =
    CirceDecoder[String].emapTry(prettyPrint =>
      GamePiece
        .fromPrettyPrint(prettyPrint)
        .toRight(
          new RuntimeException(s"$prettyPrint is not the pretty print of a GamePiece")
        )
        .toTry
    )

  given positionEncoder: CirceEncoder[Position] =
    CirceEncoder[String].contramap(_.toChessNotation)
  given positionDecoder: CirceDecoder[Position] =
    CirceDecoder[String].emapTry(Position.fromChessNotation)

  given mapCirceEncoder[K, V](using kEncoder: CirceEncoder[K], vEncoder: CirceEncoder[V]): CirceEncoder[Map[K, V]] =
    CirceEncoder[List[(K, V)]].contramap(_.toList)
  given mapCirceDecoder[K, V](using kDecoder: CirceDecoder[K], vDecoder: CirceDecoder[V]): CirceDecoder[Map[K, V]] =
    CirceDecoder[List[(K, V)]].map(_.toMap)

  given actionEncoder: CirceEncoder[GameAction] =
    CirceEncoder[Int].contramap(GameAction.allActions.indexOf)
  given actionDecoder: CirceDecoder[GameAction] =
    CirceDecoder[Int].map(GameAction.allActions(_))

  given teamEncoder: CirceEncoder[Team] =
    CirceEncoder[String].contramap(team => "Team" ++ team.toString)
  given teamDecoder: CirceDecoder[Team] =
    CirceDecoder[String].emapTry(str =>
      List(Team.Red, Team.Blue)
        .find("Team" ++ _.toString == str)
        .toRight(new RuntimeException(s"Unkown team: $str"))
        .toTry
    )

  given CirceEncoder[GameBoundaries] = deriveEncoder
  given CirceDecoder[GameBoundaries] = deriveDecoder

  given gameStateEncoder: CirceEncoder[GameState] =
    CirceEncoder[(List[(GamePiece, String)], Int, Int, GameBoundaries, Boolean)].contramap[GameState](gameState =>
      (
        gameState.pieces.toList.map((piece, position) => (piece, position.toChessNotation)),
        gameState.turnNumber,
        gameState.turnsSinceLastPieceDied,
        gameState.gameBoundaries,
        gameState.withInitialSpecialRule
      )
    )
  given gameStateDecoder: CirceDecoder[GameState] =
    CirceDecoder[(List[(GamePiece, String)], Int, Int, GameBoundaries, Boolean)].emapTry(
      (pieces, turnNumber, turnsSinceLastPieceDied, boundaries, withInitialSpecialRule) =>
        scala.util.Try {
          GameState(boundaries)(
                pieces
                  .map((piece, chessNotation) => piece -> boundaries.Position.fromChessNotation(chessNotation).get)
                  .toMap,
                turnNumber,
                turnsSinceLastPieceDied,
                withInitialSpecialRule
              )
        }
    )

  /** redInfo: AllGameInfo.PlayerInfo, blueInfo: AllGameInfo.PlayerInfo, gameHistory: GameHistory[NumRow, NumCol],
    * startTime: LocalDateTime, lastUpdateTime: LocalDateTime
    */

  given finiteDurationEncoder: CirceEncoder[FiniteDuration] =
    CirceEncoder[String].contramap(_.toString)

  given finiteDurationDecoder: CirceDecoder[FiniteDuration] =
    CirceDecoder[String].emapTry(str =>
      Try(FiniteDuration(str.takeWhile(_ != ' ').toLong, str.dropWhile(_ != ' ').tail))
    )

  given PlayerInfoCodec: CirceCodec[AllGameInfo.PlayerInfo] = deriveCodec

  given allGameInfoEncoder: CirceEncoder[AllGameInfo] =
    CirceEncoder[(AllGameInfo.PlayerInfo, AllGameInfo.PlayerInfo, GameHistory, LocalDateTime, LocalDateTime)]
      .contramap(allGameInfo =>
        (
          allGameInfo.redInfo,
          allGameInfo.blueInfo,
          allGameInfo.gameHistory,
          allGameInfo.startTime,
          allGameInfo.lastUpdateTime
        )
      )
  given allGameInfoDecoder: CirceDecoder[AllGameInfo] =
    CirceDecoder[(AllGameInfo.PlayerInfo, AllGameInfo.PlayerInfo, GameHistory, LocalDateTime, LocalDateTime)].map(
      (redInfo, blueInfo, gameHistory, startTime, lastUpdateTime) =>
        AllGameInfo(redInfo, blueInfo, gameHistory, startTime, lastUpdateTime)
    )

end MadTranslators
