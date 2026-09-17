package be.doeraene.perf

import scala.reflect.ClassTag

type NatArray[T] = Array[T]

object NatArray {
  inline def fill[T](n: Int)(t: => T)(using ClassTag[T]): NatArray[T] = Array.fill(n)(t)

  inline def from[T, CC <: Iterable[T]](col: CC)(using ClassTag[T]): NatArray[T] = Array.from(col)

  inline def empty[T](using ClassTag[T]): NatArray[T] = Array.empty[T]

  inline def apply[T](values: T*)(using ClassTag[T]): NatArray[T] = Array(values*)
}
