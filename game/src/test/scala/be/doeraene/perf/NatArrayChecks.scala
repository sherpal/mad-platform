package be.doeraene.perf

import org.scalacheck.*
import org.scalacheck.Prop.*

/** Property checks for [[NatArray]].
  *
  * [[NatArraySpecs]] pins down a handful of hand-picked cases; this pins down the actual contract, which is that a
  * `NatArray` is observationally a `Vector` for every operation it exposes. Each property is therefore the same shape:
  * run the operation on a random `NatArray`, run it on `toVector` of the same thing, and require the two answers to
  * match. That is what makes these worth having over more examples - they check the law rather than the sample, on both
  * backings at once, since `game` cross-compiles and so `Array` and `js.Array` both have to satisfy them.
  *
  * The rest are the properties that are *not* about agreeing with `Vector`: that the derived operations do not mutate
  * their receiver (an array is mutable, unlike every collection this replaced, so aliasing a result into the source is
  * a mistake the type cannot rule out), and that `update` writes exactly one slot.
  */
object NatArrayChecks extends Properties("NatArray"):

  private val ints: Gen[NatArray[Int]] =
    Gen.listOf(Gen.choose(-20, 20)).map(list => NatArray.from(list))

  private val nonEmptyInts: Gen[NatArray[Int]] =
    Gen.nonEmptyListOf(Gen.choose(-20, 20)).map(list => NatArray.from(list))

  private val strings: Gen[NatArray[String]] =
    Gen.listOf(Gen.oneOf("a", "bb", "ccc", "dddd")).map(list => NatArray.from(list))

  /* A reference type with structural equality, to keep the equality-based operations honest: `js.Array` has native
   * `indexOf`/`includes` that compare with `===`, and a check run only on `Int` or `String` would never notice if
   * those were the ones being called. */
  private val pairs: Gen[NatArray[(Int, String)]] =
    Gen.listOf(Gen.zip(Gen.choose(0, 5), Gen.oneOf("a", "b"))).map(list => NatArray.from(list))

  property("toVector of from is the original sequence") = forAll { (list: List[Int]) =>
    NatArray.from(list).toVector == list.toVector
  }

  property("map agrees with Vector") = forAll(ints) { arr =>
    arr.map(_ * 3 + 1).toVector == arr.toVector.map(_ * 3 + 1)
  }

  property("map preserves identity") = forAll(ints) { arr =>
    arr.map(identity).toVector == arr.toVector
  }

  property("map composes") = forAll(ints) { arr =>
    val f = (n: Int) => n * 2
    val g = (n: Int) => n.toString
    arr.map(f).map(g).toVector == arr.map(f andThen g).toVector
  }

  property("filter agrees with Vector") = forAll(ints) { arr =>
    arr.filter(_ % 3 == 0).toVector == arr.toVector.filter(_ % 3 == 0)
  }

  property("filter of a constantly true predicate keeps everything, false keeps nothing") = forAll(ints) { arr =>
    Prop(arr.filter(_ => true).toVector == arr.toVector) &&
    Prop(arr.filter(_ => false).length == 0)
  }

  property("flatMap agrees with Vector") = forAll(ints) { arr =>
    val f = (n: Int) => NatArray(n, n + 1)
    arr.flatMap(f).toVector == arr.toVector.flatMap(n => f(n).toVector)
  }

  property("flatMapOpt agrees with Vector") = forAll(ints) { arr =>
    val f = (n: Int) => Option.when(n % 2 == 0)(n)
    arr.flatMapOpt(f).toVector == arr.toVector.flatMap(f)
  }

  property("flatMapOpt is the same as filter map") = forAll(ints) { arr =>
    val pred = (_: Int) % 2 == 0

    arr.flatMapOpt(n => Option.when(pred(n))(n.toString)).toVector == arr.filter(pred).map(_.toString).toVector
  }

  property("flatMap of a singleton is identity") = forAll(ints) { arr =>
    arr.flatMap(NatArray(_)).toVector == arr.toVector
  }

  property("foreach visits every element in order") = forAll(ints) { arr =>
    val builder = Vector.newBuilder[Int]
    arr.foreach(builder += _)
    builder.result() == arr.toVector
  }

  property("length agrees with Vector, and nonEmpty is length > 0") = forAll(ints) { arr =>
    Prop(arr.length == arr.toVector.length) && Prop(arr.nonEmpty == (arr.length > 0))
  }

  property("apply agrees with Vector at every index") = forAll(ints) { arr =>
    val vector = arr.toVector
    (0 until arr.length).forall(index => arr(index) == vector(index))
  }

  property("head and tail decompose a non-empty array") = forAll(nonEmptyInts) { arr =>
    val vector = arr.toVector
    Prop(arr.head == vector.head) &&
    Prop(arr.tail.toVector == vector.tail) &&
    Prop(arr.tail.prepended(arr.head).toVector == vector)
  }

  property("update writes exactly one slot") = forAll(nonEmptyInts, Gen.choose(0, 19), Gen.choose(-99, 99)) {
    (arr, rawIndex, value) =>
      val before = arr.toVector
      val index  = rawIndex % arr.length
      arr.update(index, value)

      Prop(arr(index) == value) &&
      Prop(arr.length == before.length) &&
      Prop(arr.toVector == before.updated(index, value))
  }

  property("zipWithIndex agrees with Vector") = forAll(strings) { arr =>
    arr.zipWithIndex.toVector == arr.toVector.zipWithIndex
  }

  property("mkString agrees with Vector") = forAll(strings) { arr =>
    arr.mkString("|") == arr.toVector.mkString("|")
  }

  property("partition agrees with Vector") = forAll(ints) { arr =>
    val (yes, no) = arr.partition(_ > 0)
    (yes.toVector, no.toVector) == arr.toVector.partition(_ > 0)
  }

  property("partition loses nothing") = forAll(ints) { arr =>
    val (yes, no) = arr.partition(_ > 0)
    yes.length + no.length == arr.length
  }

  property("++ agrees with Vector") = forAll(ints, ints) { (left, right) =>
    (left ++ right).toVector == left.toVector ++ right.toVector
  }

  property("++ is associative") = forAll(ints, ints, ints) { (a, b, c) =>
    ((a ++ b) ++ c).toVector == (a ++ (b ++ c)).toVector
  }

  property("empty is a neutral element for ++") = forAll(ints) { arr =>
    Prop((arr ++ NatArray.empty[Int]).toVector == arr.toVector) &&
    Prop((NatArray.empty[Int] ++ arr).toVector == arr.toVector)
  }

  property("sortBy agrees with Vector") = forAll(strings) { arr =>
    arr.sortBy(_.length).toVector == arr.toVector.sortBy(_.length)
  }

  property("sortBy is a permutation that orders the keys") = forAll(ints) { arr =>
    val sorted = arr.sortBy(-_).toVector
    Prop(sorted.sorted == arr.toVector.sorted) &&
    Prop(sorted.sliding(2).forall(window => window.length < 2 || window(0) >= window(1)))
  }

  property("maxBy agrees with Vector") = forAll(nonEmptyInts) { arr =>
    arr.maxBy(n => n * n) == arr.toVector.maxBy(n => n * n)
  }

  property("indexOf agrees with Vector, on a type with structural equality") = forAll(pairs, Gen.choose(0, 5)) {
    (arr, key) =>
      val needle = (key, "a")
      arr.indexOf(needle) == arr.toVector.indexOf(needle)
  }

  property("contains agrees with Vector, on a type with structural equality") = forAll(pairs, Gen.choose(0, 5)) {
    (arr, key) =>
      val needle = (key, "a")
      arr.contains(needle) == arr.toVector.contains(needle)
  }

  property("contains is indexOf >= 0") = forAll(pairs, Gen.choose(0, 5)) { (arr, key) =>
    val needle = (key, "a")
    arr.contains(needle) == (arr.indexOf(needle) >= 0)
  }

  property("count agrees with Vector, and equals the filtered length") = forAll(ints) { arr =>
    Prop(arr.count(_ > 0) == arr.toVector.count(_ > 0)) &&
    Prop(arr.count(_ > 0) == arr.filter(_ > 0).length)
  }

  property("exists agrees with Vector, and is count > 0") = forAll(ints) { arr =>
    Prop(arr.exists(_ > 10) == arr.toVector.exists(_ > 10)) &&
    Prop(arr.exists(_ > 10) == (arr.count(_ > 10) > 0))
  }

  property("prepended agrees with Vector") = forAll(ints, Gen.choose(-99, 99)) { (arr, value) =>
    arr.prepended(value).toVector == arr.toVector.prepended(value)
  }

  property("collectFirst agrees with Vector") = forAll(ints) { arr =>
    val pf: PartialFunction[Int, String] = { case n if n % 4 == 0 => n.toString }
    arr.collectFirst(pf) == arr.toVector.collectFirst(pf)
  }

  property("groupBy agrees with Vector") = forAll(ints) { arr =>
    arr.groupBy(_ % 3).map((key, group) => key -> group.toVector) == arr.toVector.groupBy(_ % 3)
  }

  property("groupBy partitions the array") = forAll(ints) { arr =>
    arr.groupBy(_ % 3).values.map(_.length).sum == arr.length
  }

  property("par().map().toNatArray() agrees with map") = forAll(ints) { arr =>
    arr.par.map(_ + 1).toNatArray.toVector == arr.toVector.map(_ + 1)
  }

  /* An array is mutable, and every derived operation here is supposed to hand back a fresh one rather than a view
   * onto the receiver. Nothing in the type says so, so it is checked: mutate the source afterwards and the result
   * must not have moved. */
  property("derived arrays do not alias the receiver") = forAll(nonEmptyInts) { arr =>
    val mapped    = arr.map(identity)
    val filtered  = arr.filter(_ => true)
    val sorted    = arr.sortBy(identity)
    val tail      = arr.tail
    val prepended = arr.prepended(0)
    val concat    = arr ++ NatArray.empty[Int]

    val expectedMapped    = mapped.toVector
    val expectedFiltered  = filtered.toVector
    val expectedSorted    = sorted.toVector
    val expectedTail      = tail.toVector
    val expectedPrepended = prepended.toVector
    val expectedConcat    = concat.toVector

    arr.update(0, 1234)

    Prop(mapped.toVector == expectedMapped) :| "map" &&
    Prop(filtered.toVector == expectedFiltered) :| "filter" &&
    Prop(sorted.toVector == expectedSorted) :| "sortBy" &&
    Prop(tail.toVector == expectedTail) :| "tail" &&
    Prop(prepended.toVector == expectedPrepended) :| "prepended" &&
    Prop(concat.toVector == expectedConcat) :| "++"
  }

  property("derived arrays do not share structure with each other") = forAll(nonEmptyInts) { arr =>
    val first  = arr.map(identity)
    val second = arr.map(identity)

    first.update(0, 4321)

    second.toVector == arr.toVector
  }

end NatArrayChecks
