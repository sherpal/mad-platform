package be.doeraene.perf

import scala.reflect.ClassTag
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

private class ParColIfPossibleImpl[T](underlying: js.Array[T]) extends ParColIfPossible[T] {

  override def map[U](f: T => U): ParColIfPossible[U] = ParColIfPossibleImpl[U](underlying.map(f))

  override def toNatArray(using ClassTag[T]): NatArray[T] = NatArray.fromArray(underlying)

}

object ParColIfPossibleImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC): ParColIfPossible[T] = ParColIfPossibleImpl[T](col.toJSArray)

}
