package be.doeraene.perf

import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.immutable.ParVector
import scala.reflect.ClassTag

private class ParColIfPossibleImpl[T](underlying: ParVector[T]) extends ParColIfPossible[T] {
  override def toNatArray(using ClassTag[T]): NatArray[T] = NatArray.from(underlying.toVector)

  override def map[U](f: T => U): ParColIfPossible[U] = ParColIfPossibleImpl(underlying.map(f))
}

object ParColIfPossibleImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC): ParColIfPossible[T] = ParColIfPossibleImpl(col.toVector.par)

}
