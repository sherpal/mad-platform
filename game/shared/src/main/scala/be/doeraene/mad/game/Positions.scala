package be.doeraene.mad.game

import scala.compiletime.ops.int._
import scala.util.Try
import GamePiece._

object Positions {

  opaque type Row    = 0 | 1 | 2 | 3 | 4 | 5
  opaque type Column = 0 | 1 | 2 | 3

  val firstRow: Row = 0
  val lastRow: Row  = 5

  opaque type Position = (Int, Int)

  val allPositions: List[Position] = (for {
    row    <- 0 until 6
    column <- 0 until 4
  } yield Position(row, column)).toList.flatten

  object Position:
    def apply(row: Int, column: Int): Option[Position] = (row, column) match {
      case (row: Row, column: Column) => Some((row, column))
      case _                          => None
    }

    def fromChessNotation(chessNotation: String): Try[Position] = for {
      column <- Try(chessNotation.charAt(1).toString.toInt)
      row    <- Try(chessNotation.charAt(0).toString)
      position <- allPositions
        .find(_.prettyPrint == (row, column).toString)
        .toRight(new RuntimeException(s"No position exists with row $row and column $column"))
        .toTry
    } yield position

    val alphabet: Vector[Char] = LazyList.from(0).map('A'.toInt + _).take(26).toVector.map(_.toChar)

    extension (position: Position)
      def +(direction: Direction): Option[Position] = apply(position._1 + direction._1, position._2 + direction._2)
      def distanceTo(that: Position): Int           = math.abs(position._1 - that._1) + math.abs(position._2 - that._2)
      def euclideanDistanceTo(that: Position): Double = math.hypot(position._1 - that._1, position._2 - that._2)
      def asColumnAndRow: (String, Int) =
        (alphabet(position._2).toString, (1 to (lastRow + 1)).toList.reverse.apply(position._1))
      def prettyPrint: String = asColumnAndRow.toString
      def toChessNotation: String =
        val (col, row) = asColumnAndRow
        s"$col$row"
      def asPair: (Int, Int)         = position
      def isInRow(row: Row): Boolean = position._1 == row

      def asDoublePair: (Double, Double) = (position._1.toDouble, position._2.toDouble)
  end Position

  val topLeft: Position = (0, 0)

  val startingPositions: Map[GamePiece, Position] = Map(
    blue221 -> (0, 0),
    blue111 -> (0, 1),
    blue222 -> (0, 2),
    blue212 -> (0, 3),
    blue121 -> (1, 0),
    blue122 -> (1, 1),
    blue211 -> (1, 2),
    blue112 -> (1, 3),
    red121 -> (4, 0),
    red122 -> (4, 1),
    red211 -> (4, 2),
    red112 -> (4, 3),
    red221 -> (5, 0),
    red111 -> (5, 1),
    red222 -> (5, 2),
    red212 -> (5, 3)
  )

  opaque type Direction = (0 | 1 | -1, 0 | 1 | -1)
  val left: Direction   = (0, -1)
  val right: Direction  = (0, 1)
  val top: Direction    = (-1, 0)
  val bottom: Direction = (1, 0)

  extension (dir: Direction)
    def +(that: Direction): (Int, Int) = (dir._1 + that._1, dir._2 + that._2)

    def x: 0 | 1 | -1 = dir._1
    def y: 0 | 1 | -1 = dir._2

  val directions: List[Direction] = List(left, right, top, bottom)
  val all2LengthPaths: List[(Direction, Direction)] = (for {
    d1 <- directions
    d2 <- directions
    if d1._1 != -d2._1 || d1._2 != -d2._2
  } yield (d1, d2)).toList

}
