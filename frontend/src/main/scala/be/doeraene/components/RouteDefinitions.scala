package be.doeraene.components

import urldsl.language.dummyErrorImpl._
import urldsl.errors.DummyError
import scala.util.Try

object RouteDefinitions:

  val entry = root / endOfSegments

  val againstAI    = root / "against-ai"
  val againstHuman = root / "against-human"
  val playAIGame = (againstAI / "play") ? ((gameHistoryParam & teamParam).? & gameTypeParam & withInitialSpecialRuleParam)

  implicit val UUIDFromString: urldsl.vocabulary.FromString[java.util.UUID, DummyError] =
    (str: String) => Try(java.util.UUID.fromString(str)).toEither.swap.map(_ => DummyError.dummyError).swap
  implicit val UUIDPrinter: urldsl.vocabulary.Printer[java.util.UUID] = _.toString

  val gameId   = param[java.util.UUID]("gameId")
  val opponent = param[String]("opponent")
