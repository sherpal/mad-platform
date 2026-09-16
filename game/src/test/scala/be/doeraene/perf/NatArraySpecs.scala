package be.doeraene.perf

class NatArraySpecs extends munit.FunSuite {

  test("I can create a NatArray and map it") {
    val arr = NatArray(1, 2, 3)

    val result = arr.map(_.toString)

    assertEquals(result.toVector, Vector("1", "2", "3"))
  }

}
