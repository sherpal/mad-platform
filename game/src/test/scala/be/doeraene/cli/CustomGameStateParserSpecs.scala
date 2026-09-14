package be.doeraene.cli

final class CustomGameStateParserSpecs extends munit.FunSuite {

  val content = """
    |Turn Number: 34
    |Time since last expulsion: 3
    |
    |C4: Blue111
    |D5: Red111
    |
    """.stripMargin

  test("Parsing without version is considered as version 0, with shape 6 by 4") {
    assertEquals(CustomGameStateParser.parse(content).map(_.shape), Right((6, 4)))
  }

  test("Parsing the above content leads to game state with 2 pieces") {
    assertEquals(CustomGameStateParser.parse(content).map(_.pieces.size), Right(2))
  }

  test("Parsing without version with an 'E' position is invalid") {
    val newContent = content ++ "\n" ++ "E2: Blue121\n"

    assertEquals(
      CustomGameStateParser.parse(newContent).swap.map(_.getMessage),
      Right("No position exists with for E2")
    )
  }

}
