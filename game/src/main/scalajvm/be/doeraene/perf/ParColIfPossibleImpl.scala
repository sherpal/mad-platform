package be.doeraene.perf

import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.immutable.ParVector
import scala.reflect.ClassTag

private class ParColIfPossibleImpl[T](underlying: ParVector[T])(using ClassTag[T]) extends ParColIfPossible[T] {
  override def toNatArray: NatArray[T] = underlying.toArray

  override def map[U](f: T => U)(using ClassTag[U]): ParColIfPossible[U] = ParColIfPossibleImpl(underlying.map(f))
}

object ParColIfPossibleImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC)(using ClassTag[T]): ParColIfPossible[T] = ParColIfPossibleImpl(col.toVector.par)

}
