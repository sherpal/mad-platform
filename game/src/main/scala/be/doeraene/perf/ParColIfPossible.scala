package be.doeraene.perf

import scala.reflect.ClassTag

trait ParColIfPossible[T] {

  def map[U](f: T => U)(using ClassTag[U]): ParColIfPossible[U]

  def toNatArray: NatArray[T]

}

object ParColIfPossible {
  inline def fromCol[T, CC <: Iterable[T]](col: CC)(using ClassTag[T]): ParColIfPossible[T] =
    ParColIfPossibleImpl.fromCol(col)
}
