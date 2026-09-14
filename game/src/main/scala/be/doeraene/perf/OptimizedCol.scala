package be.doeraene.perf

trait OptimizedCol[T] {

  def map[U](f: T => U): OptimizedCol[U]

  def toVector: Vector[T]

}

object OptimizedCol {
  inline def fromCol[T, CC <: Iterable[T]](col: CC): OptimizedCol[T] = OptimizedColImpl.fromCol(col)
}
