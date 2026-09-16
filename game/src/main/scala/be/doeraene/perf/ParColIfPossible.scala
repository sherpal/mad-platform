package be.doeraene.perf

import scala.reflect.ClassTag

trait ParColIfPossible[T] {

  def map[U](f: T => U): ParColIfPossible[U]

  def toNatArray(using ClassTag[T]): NatArray[T]

}

object ParColIfPossible {
  inline def fromCol[T, CC <: Iterable[T]](col: CC): ParColIfPossible[T] = ParColIfPossibleImpl.fromCol(col)
}
