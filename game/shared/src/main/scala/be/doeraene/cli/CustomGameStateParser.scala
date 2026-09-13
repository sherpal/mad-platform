package be.doeraene.cli

import be.doeraene.mad.game.{GamePiece, GameState, Positions}
import be.doeraene.mad.game.GameState.AnyGameState

import java.time.LocalDateTime

import scala.util.Try
import be.doeraene.mad.game.GameBoundaries
import scala.util.Failure

/** [[GameStateParser]] reading string in format:
  * {{{
  *   # this is a comment
  *   Version: 2
  *   Shape: 6, 4
  *   With Initial Special Rule: true
  *   Turn Number: 34
  *   C4: Blue111
  *   B2: Red212
  *   ...
  *   D5: Red111
  * }}}
  */
object CustomGameStateParser extends GameStateParser:

  val commentIdentifier: String = "#"

  private final class IllegalShapeFormat(value: String)
      extends RuntimeException(
        s"The value `$value` does not properly represent a shape. Expected `n, m`."
      )
  private final class MissingShapeParam()
      extends RuntimeException(
        "Shape information was missing from the contents."
      )

  private final class UnknownShape(shape: (Int, Int))
      extends RuntimeException(
        s"Unknown shape: $shape."
      )

  private final class MissingGameTypeParam() extends RuntimeException("Game Type information was missing from the contents.")

  private final class UnknownGameType(gameType: GameBoundaries.GameType) extends RuntimeException(s"Unknown game type: $gameType")

  def shapeParser(value: String): Either[Throwable, (Int, Int)] =
    value.split(",").map(_.trim) match {
      case Array(numRowStr, numColStr) =>
        (for {
          numRow <- numRowStr.toIntOption
          numCol <- numColStr.toIntOption
        } yield (numRow, numCol)).toRight(IllegalShapeFormat(value))
      case _ => Left(IllegalShapeFormat(value))
    }

  type _6by4 = (6, 4)
  type _5by5 = (5, 5)
  type Shape = _6by4 | _5by5

  inline transparent def shapeFilter(shape: (Int, Int)): Either[Throwable, Shape] =
    shape match {
      case (6, 4) => Right((6, 4): _6by4)
      case (5, 5) => Right((5, 5): _5by5)
      case _      => Left(UnknownShape(shape))
    }

  //noinspection MapGetOrElseBoolean
  def createGameState(
      gameBoundaries: GameBoundaries
  )(map: Map[String, String]) =
    for {
      turnNumberString             <- map.get("Turn Number").toRight(new RuntimeException("Turn Number is missing"))
      turnNumber                   <- Try(turnNumberString.toInt).toEither
      withInitialSpecialRuleString <- Right(map.get("With Initial Special Rule"))
      withInitialSpecialRule       <- Try(withInitialSpecialRuleString.map(_.toBoolean).getOrElse(true)).toEither
      timeSinceLastDiedString      <- Right(map.getOrElse("Time since last expulsion", "0"))
      timeSinceLastDied            <- Try(timeSinceLastDiedString.toInt).toEither
      keysNotForPiece = List(
        "Turn Number",
        "Time since last expulsion",
        "Version",
        "Shape",
        "With Initial Special Rule",
        "Game Type"
      )
      piecesPositions <- Try(
        map
          .filterNot((key, _) => keysNotForPiece contains key)
          .map { (key, value) =>
            for {
              position <- gameBoundaries.Position
                .fromChessNotation(key)
                .recoverWith { case _: NumberFormatException =>
                  Failure(
                    new IllegalArgumentException(
                      s"This ($key) is not a valid key for a piece position. Did you mispell another key?"
                    )
                  )
                }
              piece <- GamePiece
                .fromPrettyPrint(value)
                .toRight(new RuntimeException(s"This is not a piece: $value"))
                .toTry
            } yield piece -> position
          }
          .map(_.get)
          .toMap
      ).toEither
    } yield GameState(gameBoundaries)(
      piecesPositions,
      turnNumber,
      timeSinceLastDied,
      withInitialSpecialRule
    )

  def parse(str: String): Either[Throwable, AnyGameState] =
    for {
      rawLines             <- Right(str.split(System.lineSeparator()).map(_.trim))
      linesWithoutComments <- Right(rawLines.filterNot(_.trim.startsWith(commentIdentifier)))
      keyPairs <- linesWithoutComments
        .filterNot(_.isEmpty)
        .map(_.split(":").map(_.trim))
        .map {
          case Array()           => Left(new RuntimeException("Line contains no info"))
          case Array(s)          => Left(new RuntimeException(s"Line only contained `$s` but no colon"))
          case Array(key, value) => Right((key, value))
          case theArray                 => Left(new RuntimeException(s"Line contained more than 1 colon. The array was: ${theArray.mkString(", ")}"))
        }
        .foldLeft[Either[Throwable, List[(String, String)]]](Right(List.empty[(String, String)]))(
          (maybeCurrentElements, maybeNewPair) =>
            for {
              currentElements <- maybeCurrentElements
              pair            <- maybeNewPair
            } yield pair +: currentElements
        )
      map = keyPairs.toMap
      version <- map.getOrElse("Version", "0").toIntOption.toRight(new RuntimeException("Malformed version number."))
      boundaries <-
        if version == 0 then Right(GameBoundaries.originalSixByFour) 
        else if version == 1 then map.get("Shape").toRight(MissingShapeParam()).flatMap(shapeParser).flatMap {
          case (6, 4) => Right(GameBoundaries.originalSixByFour)
          case (5, 5) => Right(GameBoundaries.defaultFiveByFive)
          case shape => Left(UnknownShape(shape))
        }
        else map.get("Game Type").toRight(MissingGameTypeParam()).flatMap(GameBoundaries.GameType.fromString).flatMap(gameType => GameBoundaries.gameBoundaryByGameType.get(gameType).toRight(UnknownGameType(gameType)))
      gameState <- createGameState(boundaries)(map)
    } yield gameState

  def generate(gameState: AnyGameState): String = {
    val c   = commentIdentifier
    val now = LocalDateTime.now
    val piecesPositionStr = gameState.pieces
      .map { (piece, position) =>
        s"${position.toChessNotation}: ${piece.prettyPrint}"
      }
      .mkString(System.lineSeparator())
    s"""
    |$c $now
    |Version: 2
    |Turn Number: ${gameState.turnNumber}
    |Game Type: ${GameBoundaries.gameTypeFromBoundary(gameState.gameBoundaries)}
    |Time since last expulsion: ${gameState.turnsSinceLastPieceDied}
    |With Initial Special Rule: ${gameState.withInitialSpecialRule}
    |
    |$piecesPositionStr
    """.stripMargin
  }

end CustomGameStateParser
