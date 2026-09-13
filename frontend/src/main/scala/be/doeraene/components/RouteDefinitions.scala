package be.doeraene.components

import be.doeraene.mad.game.GamePiece
import urldsl.language.dummyErrorImpl.*
import urldsl.errors.DummyError

import scala.util.Try

object RouteDefinitions:

  val base = root / "mad-the-game"

  val entry = base / endOfSegments

  val againstAI    = base / "against-ai"
  val againstHuman = base / "against-human"
  val playAIGame =
    (againstAI / "play") ? ((gameHistoryParam & teamParam).? & gameTypeParam & withInitialSpecialRuleParam)

  
  private val imagesMad = base / "assets" / "images-mad" 
  
  def gamePieceImagePath(piece: GamePiece) =
    imagesMad / s"${piece.prettyPrint.toLowerCase}.png"
    
  def blankPiece = imagesMad / "blank.png"

  implicit val UUIDFromString: urldsl.vocabulary.FromString[java.util.UUID, DummyError] =
    (str: String) => Try(java.util.UUID.fromString(str)).toEither.swap.map(_ => DummyError.dummyError).swap
  implicit val UUIDPrinter: urldsl.vocabulary.Printer[java.util.UUID] = _.toString

  val gameId   = param[java.util.UUID]("gameId")
  val opponent = param[String]("opponent")
