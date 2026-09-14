package be.doeraene.perf

import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.immutable.ParVector

private class OptimizedColImpl[T](underlying: ParVector[T]) extends OptimizedCol[T] {
  override def toVector: Vector[T] = underlying.toVector

  override def map[U](f: T => U): OptimizedCol[U] = OptimizedColImpl(underlying.map(f))
}

object OptimizedColImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC): OptimizedCol[T] = OptimizedColImpl(col.toVector.par)

}
