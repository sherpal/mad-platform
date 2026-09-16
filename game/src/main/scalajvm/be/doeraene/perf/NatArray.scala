package be.doeraene.perf

import io.circe.{Decoder, Encoder}

import scala.reflect.ClassTag

opaque type NatArray[T] = Array[T]

object NatArray {
  def apply[T](values: T*)(using ClassTag[T]): NatArray[T] = Array(values*)

  def from[T, CC <: Iterable[T]](col: CC)(using ClassTag[T]): NatArray[T] = col.toArray

  def fill[T](n: Int)(t: => T)(using ClassTag[T]): NatArray[T] = Array.fill(n)(t)

  def empty[T](using ClassTag[T]): NatArray[T] = Array.empty

  private[perf] inline def fromArray[T](arr: Array[T]): NatArray[T] = arr

  import NatArrayOps.*

  extension [T](arr: NatArray[T]) {
    private[perf] inline def toArray: Array[T] = arr

    def toVector: Vector[T] = Vector.from(arr)

    inline def map[U](f: T => U)(using ClassTag[U]): NatArray[U] = arr.mapOps(f)

    inline def flatMap[U](f: T => NatArray[U])(using ClassTag[U]): NatArray[U] = arr.flatMapOps(f)

    inline def foreach(f: T => Unit): Unit = {
      arr.map(f)
      ()
    }

    inline def filter(predicate: T => Boolean): NatArray[T]     = arr.filterOps(predicate)
    inline def withFilter(predicate: T => Boolean): NatArray[T] = arr.filter(predicate)

    inline def length: Int = arr.lengthOps

    inline def apply(index: Int): T = arr.applyOps(index)

    inline def update(index: Int, t: T): Unit = arr.updateOps(index, t)

    inline def zipWithIndex: NatArray[(T, Int)] = arr.zipWithIndexOps

    inline def mkString(sep: String): String = arr.mkStringOps(sep)

    inline def head: T = arr.headOps

    inline def partition(predicate: T => Boolean): (NatArray[T], NatArray[T]) = arr.partitionOps(predicate)

    inline def ++[U >: T, X <: U](that: NatArray[X])(using ClassTag[U]): NatArray[U] = arr.concatOps(that)

    inline def nonEmpty: Boolean = arr.length > 0

    inline def tail: NatArray[T] = arr.tailOps

    inline def par: ParColIfPossible[T] = ParColIfPossible.fromCol(arr)

    inline def sortBy[U](by: T => U)(using Ordering[U]): NatArray[T] = arr.sortByOps(by)

    inline def maxBy[U](f: T => U)(using Ordering[U]): T = arr.maxByOps(f)

    inline def indexOf(t: T): Int = arr.indexOfOps(t)

    inline def count(p: T => Boolean): Int = arr.countOps(p)

    inline def exists(p: T => Boolean): Boolean = arr.existsOps(p)

    inline def contains(t: T): Boolean = arr.containsOps(t)

    inline def prepended(t: T)(using ClassTag[T]): NatArray[T] = arr.prependedOps(t)

    inline def collectFirst[U](pf: PartialFunction[T, U]): Option[U] = arr.collectFirstOps(pf)

    inline def groupBy[K](f: T => K)(using ClassTag[T]): Map[K, NatArray[T]] = arr.groupByOps(f)
  }

  object Converters {
    extension [T, CC <: Iterable[T]](col: CC) {
      def toNatArray(using ClassTag[T]): NatArray[T] = from(col)
    }
  }

  given [T](using Encoder[T]): Encoder[NatArray[T]]              = summon[Encoder[Vector[T]]].contramap(_.toVector)
  given [T](using Decoder[T], ClassTag[T]): Decoder[NatArray[T]] = summon[Decoder[Vector[T]]].map(Converters.toNatArray)

  given [T](using ClassTag[T]): Conversion[Option[T], NatArray[T]] = _.fold(empty)(apply(_))

}
