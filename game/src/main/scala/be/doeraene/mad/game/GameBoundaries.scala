package be.doeraene.mad.game

import Ranges.*
import scala.util.Try
import Positions.Direction
import scala.compiletime.ops.int.{< => <<}
import scala.compiletime.ops.int.{>= => >>=}
import scala.language.implicitConversions

import scala.compiletime.constValue

sealed trait GameBoundaries {

  val alphabet: Vector[Char] = LazyList.from(0).map('A'.toInt + _).take(26).toVector.map(_.toChar)

  def lastRow: Int
  def lastCol: Int
  def isExistingPosition(row: Int, col: Int): Boolean

  opaque type Position = (Int, Int)

  @inline def positionFromOtherBoundaries(other: GameBoundaries)(position: other.Position): Position =
    (position.row, position.col)

  object Position {
    def apply(row: Int, col: Int): Option[Position] = Option.when(isExistingPosition(row, col))((row, col))

    extension (self: Position)
      def row: Int = self._1
      def col: Int = self._2

      def +(that: Direction): Option[Position] = apply(self.row + that.x, self.col + that.y)

      def distanceTo(that: Position): Int = math.abs(self._1 - that._1.toInt) + math.abs(self._2 - that._2.toInt)

      def euclideanDistanceTo(that: Position): Double = math.hypot(self._1 - that._1.toInt, self._2 - that._2.toInt)
      def asColumnAndRow: (String, Int) =
        (alphabet(self._2.toInt).toString, (1 to lastRow).toList.reverse.apply(self._1.toInt))
      def prettyPrint: String = toChessNotation

      def toChessNotation: String =
        val (col, row) = asColumnAndRow
        s"$col$row"

      def asPair: (Int, Int) = (self._1.toInt, self._2.toInt)

      def isInRow(row: Int): Boolean = self._1 == row

      def asDoublePair: (Double, Double) = (self._1.toDouble, self._2.toDouble)

    def fromChessNotation(chessNotation: String): Try[Position] = allPositions
      .find(_.prettyPrint == chessNotation)
      .toRight(new RuntimeException(s"No position exists with for $chessNotation"))
      .toTry

  }

  lazy val allPositions: List[Position] = (for {
    row <- 0 until lastRow
    col <- 0 until lastCol
  } yield Position(row, col)).toList.flatten

  /** Reflects a position across the horizontal mid-line of the board, keeping its column.
    *
    * Together with swapping the two teams this is the symmetry that lets a network see every position from the mover's
    * point of view (see [[be.doeraene.mad.ai.nn.Canonical]]). Defined here rather than next to the rest of the AI code
    * because `Position` is only transparent inside this trait.
    *
    * Only meaningful on a board whose shape is [[isVerticallySymmetric]], which all four current boundaries are.
    */
  def mirrorVertically(position: Position): Position = (lastRow - 1 - position._1, position._2)

  /** Whether the playable squares of this board are unchanged by [[mirrorVertically]].
    *
    * A precondition of the canonicalisation: on an asymmetric board the mirror of a legal position could fall in a
    * hole.
    */
  lazy val isVerticallySymmetric: Boolean = (0 until lastRow).forall { row =>
    (0 until lastCol).forall(col => isExistingPosition(row, col) == isExistingPosition(lastRow - 1 - row, col))
  }

  lazy val rows: Vector[Vector[Option[Position]]] = for {
    row <- (0 until lastRow).toVector
  } yield for {
    col <- (0 until lastCol).toVector
  } yield Position(row, col)

  val topLeft: Position = (0, 0)

  def startingPositions: Map[GamePiece, Position]

  def gameType: GameBoundaries.GameType = GameBoundaries.gameTypeFromBoundary(this)

  lazy val bonusActionPositions: Team => Set[Position] = {
    def forOpponentOf(team: Team): Set[Position] =
      startingPositions.toList
        .collect {
          case (piece, position) if piece.team == team => position: Position
        }
        .toSet
        .filter((startPosition: Position) =>
          !allPositions.exists(legalPosition =>
            startPosition._2 == legalPosition._2 && ((legalPosition._1 - startPosition._1) * (team
              .firstRow(lastRow) - team.otherTeam.firstRow(lastRow)) > 0)
          )
        )

    // this is an "expensive" computation so we do it only once.
    val forBlue = forOpponentOf(Team.Red)
    val forRed  = forOpponentOf(Team.Blue)
    {
      case Team.Blue => forBlue
      case Team.Red  => forRed
    }
  }

}

object GameBoundaries:

  import GamePiece.*

  sealed trait RectangularGameBoundaries(numRows: Int, numCols: Int) extends GameBoundaries {
    final def lastRow: Int = numRows
    final def lastCol: Int = numCols

    def isExistingPosition(row: Int, col: Int): Boolean = 0 <= row && row < numRows && 0 <= col && col < numCols
  }

  case class DefaultFiveByFive() extends RectangularGameBoundaries(5, 5) {
    def startingPositions: Map[GamePiece, Position] = Map(
      blue221 -> Position(0, 0),
      blue111 -> Position(0, 1),
      blue211 -> Position(0, 2),
      blue222 -> Position(0, 3),
      blue212 -> Position(0, 4),
      blue121 -> Position(1, 0),
      blue122 -> Position(1, 2),
      blue112 -> Position(1, 4),
      red121 -> Position(3, 0),
      red122 -> Position(3, 2),
      red112 -> Position(3, 4),
      red221 -> Position(4, 0),
      red111 -> Position(4, 1),
      red211 -> Position(4, 2),
      red222 -> Position(4, 3),
      red212 -> Position(4, 4)
    ).collect { case (piece, Some(position)) =>
      piece -> position
    }
  }

  case class OriginalSixByFour() extends RectangularGameBoundaries(6, 4) {
    def startingPositions = Map(
      blue221 -> Position(0, 0),
      blue111 -> Position(0, 1),
      blue222 -> Position(0, 2),
      blue212 -> Position(0, 3),
      blue121 -> Position(1, 0),
      blue122 -> Position(1, 1),
      blue211 -> Position(1, 2),
      blue112 -> Position(1, 3),
      red121 -> Position(4, 0),
      red122 -> Position(4, 1),
      red211 -> Position(4, 2),
      red112 -> Position(4, 3),
      red221 -> Position(5, 0),
      red111 -> Position(5, 1),
      red222 -> Position(5, 2),
      red212 -> Position(5, 3)
    ).collect { case (piece, Some(position)) =>
      piece -> position
    }
  }

  case class DefaultFourBySix() extends GameBoundaries {

    val excludedPositions = Set(
      (0, 0),
      (0, 1),
      (0, 4),
      (0, 5),
      (5, 0),
      (5, 1),
      (5, 4),
      (5, 5)
    )

    def isExistingPosition(row: Int, col: Int): Boolean =
      0 <= row && row < lastRow && 0 <= col && col < lastCol && !excludedPositions.contains((row, col))

    final def lastRow: Int = 6
    final def lastCol: Int = 6

    def startingPositions = Map(
      blue221 -> Position(1, 0),
      blue111 -> Position(0, 2),
      blue222 -> Position(0, 3),
      blue212 -> Position(1, 4),
      blue121 -> Position(1, 1),
      blue122 -> Position(1, 2),
      blue211 -> Position(1, 3),
      blue112 -> Position(1, 5),
      red121 -> Position(4, 1),
      red122 -> Position(4, 2),
      red211 -> Position(4, 3),
      red112 -> Position(4, 5),
      red221 -> Position(4, 0),
      red111 -> Position(5, 2),
      red222 -> Position(5, 3),
      red212 -> Position(4, 4)
    ).collect { case (piece, Some(position)) =>
      piece -> position
    }
  }

  case class AztecDiamond() extends GameBoundaries {
    final def lastRow: Int = 7
    final def lastCol: Int = 6
    val excludedPositions = Set(
      (0, 0),
      (0, 1),
      (0, 4),
      (0, 5),
      (1, 0),
      (1, 5),
      (5, 0),
      (5, 5),
      (6, 0),
      (6, 1),
      (6, 4),
      (6, 5)
    )
    def isExistingPosition(row: Int, col: Int): Boolean =
      0 <= row && row < lastRow && 0 <= col && col < lastCol && !excludedPositions.contains((row, col))

    def startingPositions = Map(
      blue221 -> Position(1, 1),
      blue111 -> Position(0, 2),
      blue222 -> Position(0, 3),
      blue212 -> Position(1, 4),
      blue121 -> Position(2, 2),
      blue122 -> Position(1, 2),
      blue211 -> Position(1, 3),
      blue112 -> Position(2, 3),
      red121 -> Position(4, 2),
      red122 -> Position(5, 2),
      red211 -> Position(5, 3),
      red112 -> Position(4, 3),
      red221 -> Position(5, 1),
      red111 -> Position(6, 2),
      red222 -> Position(6, 3),
      red212 -> Position(5, 4)
    ).collect { case (piece, Some(position)) =>
      piece -> position
    }
  }

  val originalSixByFour: GameBoundaries      = OriginalSixByFour()
  val defaultFiveByFive: GameBoundaries      = DefaultFiveByFive()
  val defaultFourBySix: GameBoundaries       = DefaultFourBySix()
  val aztecDiamondBoundaries: GameBoundaries = AztecDiamond()

  opaque type GameType = String
  val _6by4: GameType        = "6 by 4"
  val _5by5: GameType        = "5 by 5"
  val _4by6: GameType        = "4 by 6"
  val aztecDiamond: GameType = "Aztec Diamond"

  final class GameTypeDoesNotExist(value: String)
      extends RuntimeException(s"The following value is not a game type: $value.")

  object GameType {
    private val existingGameTypes: Set[GameType] = Set(
      _4by6,
      _5by5,
      _6by4,
      aztecDiamond
    )
    def fromString(str: String): Either[GameTypeDoesNotExist, GameType] =
      Either.cond(existingGameTypes.contains(str), str, new GameTypeDoesNotExist(str))

    extension (gameType: GameType) def value: String = gameType.toString
  }

  val gameBoundaryByGameType: Map[GameType, GameBoundaries] = Map(
    _6by4 -> OriginalSixByFour(),
    _5by5 -> DefaultFiveByFive(),
    _4by6 -> DefaultFourBySix(),
    aztecDiamond -> AztecDiamond()
  )

  def gameTypeFromBoundary(boundaries: GameBoundaries): GameType = boundaries match {
    case _: OriginalSixByFour => _6by4
    case _: DefaultFiveByFive => _5by5
    case _: DefaultFourBySix  => _4by6
    case _: AztecDiamond      => aztecDiamond
  }

end GameBoundaries
