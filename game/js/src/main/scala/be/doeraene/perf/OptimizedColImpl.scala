package be.doeraene.perf

import scala.scalajs.js.JSConverters.*
import scala.scalajs.js

private class OptimizedColImpl[T](underlying: js.Array[T]) extends OptimizedCol[T] {

  override def map[U](f: T => U): OptimizedCol[U] = OptimizedColImpl[U](underlying.map(f))

  override def toVector: Vector[T] = underlying.toVector

}

object OptimizedColImpl {

  def fromCol[T, CC <: Iterable[T]](col: CC): OptimizedCol[T] = OptimizedColImpl[T](col.toJSArray)

}
