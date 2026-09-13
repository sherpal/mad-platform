package be.doeraene.mad.game

import scala.language.implicitConversions
import scala.compiletime.ops.int.{< => <<}
import scala.compiletime.ops.int.{>= => >>=}

object Ranges:

  opaque type <[From <: Int, To <: Int] = Int

  object `<` :

    def apply[From <: Int: ValueOf, To <: Int: ValueOf](n: Int): Option[From < To] =
      Option.when(valueOf[From] <= n && n < valueOf[To])(n.asInstanceOf[From < To])

    def apply[N <: Int: ValueOf, From <: Int: ValueOf, To <: Int: ValueOf](using
        true =:= (N >>= From),
        true =:= (N << To)
    ): From < To = valueOf[N]

    extension [From <: Int, To <: Int](self: From < To)
      @inline def toInt: Int = self

      @inline def toDouble: Double = self.toDouble

    extension [From <: Int: ValueOf, To <: Int: ValueOf](self: From < To)
      def +(that: Int): Option[From < To] = apply((self: Int) + that)

      def -(that: Int): Int = self - that

      def minValue: From = valueOf[From]

    implicit def fromInt[N <: Int, From <: Int, To <: Int](
        n: N
    )(using true =:= (N << To), true =:= (N >>= From)): From < To =
      n

  end <

  trait Bounds[From <: Int, To <: Int, R <: From < To] {
    def lowerBound: From < To
    def upperBound: Int
    def biggestValue: From < To

    override def toString: String = s"[$lowerBound, $upperBound]"
  }

  object Bounds {
    def apply[From <: Int, To <: Int](using ValueOf[From], ValueOf[To]): Bounds[From, To, From < To] =
      new Bounds[From, To, From < To] {
        def lowerBound: From < To   = valueOf[From]
        def upperBound: Int         = valueOf[To]
        def biggestValue: From < To = valueOf[To] - 1
      }
  }

end Ranges
