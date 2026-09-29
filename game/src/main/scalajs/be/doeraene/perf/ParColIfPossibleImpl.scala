package be.doeraene.perf

import scala.reflect.ClassTag

private class ParColIfPossibleImpl[T](underlying: Array[T]) extends ParColIfPossible[T] {

  override def map[U](f: T => U)(using ClassTag[U]): ParColIfPossible[U] = ParColIfPossibleImpl[U](underlying.map(f))

  override def toNatArray: NatArray[T] = underlying

}

object ParColIfPossibleImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC)(using ClassTag[T]): ParColIfPossible[T] =
    ParColIfPossibleImpl[T](col.toArray)

}
