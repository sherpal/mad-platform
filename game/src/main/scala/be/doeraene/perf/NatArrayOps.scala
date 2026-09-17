package be.doeraene.perf

import scala.reflect.ClassTag

private[perf] object NatArrayOps {

  extension [T](arr: NatArray[T]) {
    inline def mapOps[U](f: T => U)(using ClassTag[U]): NatArray[U] = NatArray.fromArray(arr.toArray.map(f))

    inline def flatMapOps[U](f: T => NatArray[U])(using ClassTag[U]): NatArray[U] =
      NatArray.fromArray(arr.toArray.flatMap(f(_).toArray))

    inline def flatMapItOps[U](f: T => IterableOnce[U])(using ClassTag[U]): NatArray[U] =
      NatArray.fromArray(arr.toArray.flatMap(f))

    inline def foreachOps(f: T => Unit): Unit = arr.toArray.foreach(f)

    inline def filterOps(predicate: T => Boolean): NatArray[T] = NatArray.fromArray(arr.toArray.filter(predicate))

    inline def zipWithIndexOps: NatArray[(T, Int)] = NatArray.fromArray(arr.toArray.zipWithIndex)

    inline def lengthOps: Int = arr.toArray.length

    inline def applyOps(index: Int): T = arr.toArray(index)

    inline def updateOps(index: Int, value: T): Unit =
      arr.toArray(index) = value

    inline def mkStringOps(sep: String): String = arr.toArray.mkString(sep)

    inline def headOps: T = arr.toArray.head

    inline def partitionOps(predicate: T => Boolean): (NatArray[T], NatArray[T]) = {
      val (left, right) = arr.toArray.partition(predicate)
      (NatArray.fromArray(left), NatArray.fromArray(right))
    }

    /* Concatenated through the platform's own `++`, which knows to build a result array of `U`. Re-typing the
     * argument with `asInstanceOf[NatArray[U]]` instead was a lie about the runtime array: on the JVM an
     * `Array[Int]` really is an `int[]`, so widening to a supertype element type - `NatArray[Int] ++ ...` seen as
     * `NatArray[AnyVal]` - threw a `ClassCastException` rather than boxing. */
    inline def concatOps[U >: T, X <: U](that: NatArray[X])(using ClassTag[U]): NatArray[U] =
      NatArray.fromArray(arr.toArray ++ that.toArray)

    inline def tailOps: NatArray[T] = NatArray.fromArray(arr.toArray.slice(1, arr.lengthOps))

    inline def sortByOps[U](by: T => U)(using Ordering[U]): NatArray[T] =
      NatArray.fromArray(arr.toArray.sortBy(by))

    inline def maxByOps[U](f: T => U)(using Ordering[U]): T = arr.toArray.maxBy(f)

    inline def indexOfOps(t: T): Int = arr.toArray.indexOf(t)

    inline def countOps(p: T => Boolean): Int = arr.toArray.count(p)

    inline def existsOps(p: T => Boolean): Boolean = arr.toArray.exists(p)

    inline def containsOps(t: T): Boolean = arr.toArray.contains(t)

    inline def prependedOps(t: T)(using ClassTag[T]): NatArray[T] = NatArray.fromArray(arr.toArray.prepended(t))

    inline def collectFirstOps[U](pf: PartialFunction[T, U]): Option[U] = arr.toArray.collectFirst(pf)

    inline def groupByOps[U](f: T => U)(using ClassTag[T]): Map[U, NatArray[T]] =
      arr.toArray.groupBy(f).map((u, arr) => u -> NatArray.from(arr))
  }

}
