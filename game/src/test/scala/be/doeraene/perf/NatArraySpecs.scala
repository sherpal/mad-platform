package be.doeraene.perf

import io.circe.syntax.*
import io.circe.parser.decode

import NatArray.Converters.*

/** Unit tests for [[NatArray]].
  *
  * The whole point of the type is that it *is* the platform's native array, so there is no shared implementation to
  * test once: `game` cross-compiles, and these run on both the JVM (where the underlying thing is `Array`) and Scala.js
  * (where it is `js.Array`). Anything asserted here is therefore asserted about both backings, which is the only way a
  * divergence between the two - the real risk of this design - ever shows up.
  *
  * The reference semantics throughout is `Vector`'s: a `NatArray` is meant to behave "roughly like any other
  * collection" for callers, so every operation is checked against what the equivalent `Vector` operation does.
  */
class NatArraySpecs extends munit.FunSuite {

  private def assertContent[T](arr: NatArray[T], expected: Vector[T])(using munit.Location): Unit =
    assertEquals(arr.toVector, expected)

  test("I can create a NatArray and map it") {
    val arr = NatArray(1, 2, 3)

    val result = arr.map(_.toString)

    assertContent(result, Vector("1", "2", "3"))
  }

  test("apply builds a NatArray holding exactly the given values, in order") {
    assertContent(NatArray(1, 2, 3), Vector(1, 2, 3))
    assertContent(NatArray("only"), Vector("only"))
  }

  test("empty is empty, and nonEmpty agrees with length") {
    val empty = NatArray.empty[Int]

    assertEquals(empty.length, 0)
    assertEquals(empty.nonEmpty, false)
    assertContent(empty, Vector.empty)

    assertEquals(NatArray(1).nonEmpty, true)
  }

  test("from copies any Iterable, keeping its iteration order") {
    assertContent(NatArray.from(List(1, 2, 3)), Vector(1, 2, 3))
    assertContent(NatArray.from(Vector("a", "b")), Vector("a", "b"))
    assertContent(NatArray.from(Set(42)), Vector(42))
    assertContent(NatArray.from(List.empty[Int]), Vector.empty)
  }

  test("toNatArray is from, spelled as an extension") {
    assertContent(List(1, 2, 3).toNatArray, Vector(1, 2, 3))
  }

  test("fill produces n elements") {
    assertContent(NatArray.fill(3)("x"), Vector("x", "x", "x"))
    assertContent(NatArray.fill(0)("x"), Vector.empty)
  }

  test("fill re-evaluates its by-name element for every slot") {
    var counter = 0
    val arr     = NatArray.fill(4) { counter += 1; counter }

    assertEquals(counter, 4)
    assertContent(arr, Vector(1, 2, 3, 4))
  }

  test("apply(index) and update(index, t) read and write in place") {
    val arr = NatArray(1, 2, 3)

    assertEquals(arr(0), 1)
    assertEquals(arr(2), 3)

    arr.update(1, 20)

    assertEquals(arr(1), 20)
    assertContent(arr, Vector(1, 20, 3))
  }

  test("head and tail behave like Vector's") {
    val arr = NatArray(1, 2, 3)

    assertEquals(arr.head, 1)
    assertContent(arr.tail, Vector(2, 3))
    assertContent(NatArray(1).tail, Vector.empty)
  }

  test("tail returns a fresh array rather than a view on the original") {
    val arr  = NatArray(1, 2, 3)
    val tail = arr.tail

    arr.update(1, 20)

    assertContent(tail, Vector(2, 3))
  }

  test("map, filter and flatMap agree with Vector") {
    val arr = NatArray(1, 2, 3, 4)

    assertContent(arr.map(_ * 2), Vector(2, 4, 6, 8))
    assertContent(arr.filter(_ % 2 == 0), Vector(2, 4))
    assertContent(arr.flatMap(n => NatArray.fill(n)(n)), Vector(1, 2, 2, 3, 3, 3, 4, 4, 4, 4))
    assertContent(arr.flatMap(_ => NatArray.empty[Int]), Vector.empty)
  }

  test("flatMapOpt agrees with Vector") {
    val arr = NatArray(1, 2, 3, 4)

    assertContent(arr.flatMapOpt(x => Some(x).filter(_ % 2 == 0)), Vector(2, 4))
  }

  test("map does not mutate the receiver") {
    val arr = NatArray(1, 2, 3)

    arr.map(_ * 10)

    assertContent(arr, Vector(1, 2, 3))
  }

  test("foreach runs the effect once per element, in order") {
    val builder = Vector.newBuilder[Int]

    NatArray(1, 2, 3).foreach(builder += _)

    assertEquals(builder.result(), Vector(1, 2, 3))
  }

  test("a for-comprehension with a guard filters, through withFilter") {
    val result = for {
      n <- NatArray(1, 2, 3, 4, 5)
      if n % 2 == 1
    } yield n * n

    assertContent(result, Vector(1, 9, 25))
  }

  test("a nested for-comprehension flattens, through flatMap") {
    val result = for {
      letter <- NatArray("a", "b")
      number <- NatArray(1, 2)
    } yield s"$letter$number"

    assertContent(result, Vector("a1", "a2", "b1", "b2"))
  }

  test("zipWithIndex pairs every element with its position") {
    assertContent(NatArray("a", "b", "c").zipWithIndex, Vector(("a", 0), ("b", 1), ("c", 2)))
    assertContent(NatArray.empty[String].zipWithIndex, Vector.empty)
  }

  test("mkString joins with the separator") {
    assertEquals(NatArray(1, 2, 3).mkString("-"), "1-2-3")
    assertEquals(NatArray(1).mkString("-"), "1")
    assertEquals(NatArray.empty[Int].mkString("-"), "")
  }

  test("partition splits into matching and non-matching, keeping order") {
    val (even, odd) = NatArray(1, 2, 3, 4, 5).partition(_ % 2 == 0)

    assertContent(even, Vector(2, 4))
    assertContent(odd, Vector(1, 3, 5))
  }

  test("++ concatenates") {
    assertContent(NatArray(1, 2) ++ NatArray(3, 4), Vector(1, 2, 3, 4))
    assertContent(NatArray.empty[Int] ++ NatArray(1), Vector(1))
    assertContent(NatArray(1) ++ NatArray.empty[Int], Vector(1))
  }

  test("++ widens to a common supertype") {
    val ints: NatArray[Int]   = NatArray(1, 2)
    val more: NatArray[Int]   = NatArray(3)
    val all: NatArray[AnyVal] = ints.++[AnyVal, Int](more)

    assertContent(all, Vector[AnyVal](1, 2, 3))
  }

  test("++ does not alias either operand") {
    val left  = NatArray(1, 2)
    val right = NatArray(3, 4)
    val both  = left ++ right

    left.update(0, 100)
    right.update(0, 300)

    assertContent(both, Vector(1, 2, 3, 4))
  }

  test("sortBy sorts on the key") {
    assertContent(NatArray("ccc", "a", "bb").sortBy(_.length), Vector("a", "bb", "ccc"))
    assertContent(NatArray(3, 1, 2).sortBy(-_), Vector(3, 2, 1))
  }

  test("sortBy leaves the receiver alone") {
    val arr = NatArray(3, 1, 2)

    arr.sortBy(identity)

    assertContent(arr, Vector(3, 1, 2))
  }

  test("maxBy returns the element with the largest key, first one winning ties") {
    assertEquals(NatArray("a", "ccc", "bb").maxBy(_.length), "ccc")
    assertEquals(NatArray("ab", "cd").maxBy(_.length), "ab")
  }

  test("indexOf, contains, count and exists use value equality") {
    val arr = NatArray("a", "b", "c", "b")

    assertEquals(arr.indexOf("b"), 1)
    assertEquals(arr.indexOf("z"), -1)
    assertEquals(arr.contains("c"), true)
    assertEquals(arr.contains("z"), false)
    assertEquals(arr.count(_ == "b"), 2)
    assertEquals(arr.exists(_ == "c"), true)
    assertEquals(arr.exists(_ == "z"), false)
  }

  test("indexOf and contains compare by equals, not by reference") {
    /* Reference types whose `equals` is structural are the interesting case: the underlying `js.Array` has a *native*
     * `indexOf`/`includes` that compares with `===`, and picking those up instead of the collection ones would make
     * this pass on the JVM and fail in the browser. Several callers rely on the structural answer -
     * `MadTranslators` encodes a `GameAction` as `GameAction.allActions.indexOf(action)`. */
    val needle = (1, "x")
    val arr    = NatArray((0, "w"), (1, "x"), (2, "y"))

    assertEquals(arr.indexOf(needle), 1)
    assertEquals(arr.contains(needle), true)
  }

  test("prepended puts the element in front") {
    assertContent(NatArray(2, 3).prepended(1), Vector(1, 2, 3))
    assertContent(NatArray.empty[Int].prepended(1), Vector(1))
  }

  test("collectFirst returns the first defined image, or None") {
    val arr = NatArray(1, 2, 3, 4)

    assertEquals(arr.collectFirst { case n if n % 2 == 0 => n * 10 }, Some(20))
    assertEquals(arr.collectFirst { case n if n > 100 => n }, None)
    assertEquals(NatArray.empty[Int].collectFirst { case n => n }, None)
  }

  test("groupBy groups by key, keeping relative order inside a group") {
    val grouped = NatArray(1, 2, 3, 4, 5).groupBy(_ % 2)

    assertEquals(grouped.keySet, Set(0, 1))
    assertEquals(grouped(0).toVector, Vector(2, 4))
    assertEquals(grouped(1).toVector, Vector(1, 3, 5))
    assertEquals(NatArray.empty[Int].groupBy(identity).size, 0)
  }

  test("par().map().toNatArray() maps every element") {
    /* `par` is the one place where the two platforms genuinely do different things (real parallel collections on the
     * JVM, a plain pass on JS), so what is asserted is only what both must agree on: the result. */
    assertContent(NatArray(1, 2, 3).par.map(_ * 2).toNatArray, Vector(2, 4, 6))
    assertContent(NatArray.empty[Int].par.map(_ * 2).toNatArray, Vector.empty)
  }

  test("a NatArray round-trips through its circe codec") {
    val arr = NatArray(1, 2, 3)

    assertEquals(arr.asJson.noSpaces, Vector(1, 2, 3).asJson.noSpaces)
    assertEquals(decode[NatArray[Int]](arr.asJson.noSpaces).map(_.toVector), Right(Vector(1, 2, 3)))
    assertEquals(decode[NatArray[Int]]("[]").map(_.toVector), Right(Vector.empty[Int]))
  }

}
