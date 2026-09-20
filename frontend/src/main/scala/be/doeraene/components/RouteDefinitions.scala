package be.doeraene.components

import be.doeraene.mad.game.GamePiece
import urldsl.errors.DummyError
import urldsl.errors.DummyError.dummyError
import urldsl.language.dummyErrorImpl.*
import urldsl.vocabulary.{FromString, Printer}

import scala.util.Try

//noinspection TypeAnnotation
object RouteDefinitions:

  val base  = root / "mad-the-game"
  val entry = base / endOfSegments

  val againstAI = base / "against-ai"
  val playAIGame =
    (againstAI / "play") ? (gameHistoryParam.? & gameTypeParam & gameOptionsParam)

  private val imagesMad = base / "assets" / "images-mad"

  val gamePieceImagePath = imagesMad / segment[GamePiece]
  val blankPiece         = imagesMad / "blank.png"

  given Printer[GamePiece] = _.prettyPrint.toLowerCase ++ ".png"
  given FromString[GamePiece, DummyError] = (str: String) =>
    GamePiece.pieces.find(summon[Printer[GamePiece]].print(_) == str.dropRight(".png".length)).toRight(dummyError)
