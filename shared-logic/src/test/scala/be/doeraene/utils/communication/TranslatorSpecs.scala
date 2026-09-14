package be.doeraene.utils.communication

import io.circe.generic.auto.*

import be.doeraene.mad.game.*
import be.doeraene.mad.game.Positions.Position
import MadTranslators.given

final class TranslatorSpecs extends munit.FunSuite:

  val translator = JsonTranslator[ACaseClass]

  val aCaseClassJson = """
  |{
  | "foo": "hello",
  | "bar": 5
  |}
  """.stripMargin

  test("Json Translator should be summoned") {
    assert(translator.encode(ACaseClass("truc", 3)).asString.contains("truc"))
    assertEquals(translator.decode(Translator.Json.fromString(aCaseClassJson)), Right(ACaseClass("hello", 5)))
  }

  test("Writing a game piece") {
    assertEquals(JsonTranslator[GamePiece].encode(GamePiece.blue111).asString, """"Blue111"""")
  }

  test("Writing a position") {
    assertEquals(JsonTranslator[Position].encode(Positions.topLeft).asString, """"A6"""")
  }

end TranslatorSpecs
