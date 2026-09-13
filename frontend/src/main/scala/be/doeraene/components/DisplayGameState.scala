package be.doeraene.components

import com.raquo.laminar.api.L._
import be.doeraene.mad.game._
import be.doeraene.mad.game.Positions._

object DisplayGameState:

  private sealed trait CssClassGameSize {
    def repr: String = toString.drop(1)
  }
  private case object _5x5 extends CssClassGameSize
  private case object _6x4 extends CssClassGameSize

  private def fromGameType(gameType: GameBoundaries.GameType): CssClassGameSize = gameType match {
    case GameBoundaries._6by4        => _6x4
    case GameBoundaries._4by6        => _6x4
    case GameBoundaries._5by5        => _5x5
    case GameBoundaries.aztecDiamond => _6x4
    case _                           => throw new IllegalArgumentException(s"Unknown game type: $gameType")
  }

  /** Component showing the board, from the perspective of the specified team. The board is displayed in such a way that
    * the player's side is at the bottom.
    */
  def apply(
      boundaries: GameBoundaries,
      gameStates: Signal[GameState],
      maybeAdditionActionStream: Signal[Option[GameAction]],
      team: Team,
      hoveredPieceObserver: Observer[Option[GamePiece]],
      pieceClickObserver: Observer[Option[GamePiece]],
      maybeSelectedPieceSignal: Signal[Option[GamePiece]]
  ): HtmlElement =
    val images = GamePiece.pieces.map { piece =>
      piece -> img(
        src   := ("/" ++ RouteDefinitions.gamePieceImagePath(piece).createPath()),
        width := "64px"
      )
    }.toMap

    val blanks = (for {
      row <- 0 until boundaries.lastRow
      col <- 0 until boundaries.lastCol
      rowCol = (row, col)
    } yield rowCol -> img(src := ("/" ++ RouteDefinitions.blankPiece.createPath()), width := "64px")).toMap

    div(
      child <-- maybeAdditionActionStream
        .combineWith(gameStates)
        .map {
          case (Some(action), gameState) => action.act(gameState)
          case (_, gameState)            => gameState
        }
        .map(display(_, images, blanks, team, hoveredPieceObserver, pieceClickObserver, maybeSelectedPieceSignal))
    )

  private def display(
      gameState: GameState,
      images: Map[GamePiece, HtmlElement],
      blanks: Map[(Int, Int), HtmlElement],
      team: Team,
      hoveredPieceObserver: Observer[Option[GamePiece]],
      pieceClickObserver: Observer[Option[GamePiece]],
      maybeSelectedPieceSignal: Signal[Option[GamePiece]]
  ) = {
    val boundaries = gameState.gameBoundaries

    val cssClass = fromGameType(gameState.gameType)

    def symmetry[A]: List[A] => List[A] = if team == Team.Blue then _.reverse else identity

    def isBlackSquare(rowIndex: Int, colIndex: Int) =
      className := (if (rowIndex + colIndex) % 2 == 0 then "black-square-" ++ cssClass.repr else "white-square")

    val withBorderCls = "with-border"
    val rows =
      gameState.gameBoundaries.rows.toList.zipWithIndex.map { (row, rowIndex) =>
        tr(
          td(className := "row-index", (boundaries.lastRow - rowIndex).toString),
          symmetry(row.toList.zipWithIndex.map {
            case (None, colIndex) =>
              td(
                className := withBorderCls,
                className := "illegal-square",
                blanks((rowIndex, colIndex)),
                onMouseEnter.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                onMouseLeave.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                onClick.mapTo(None) --> pieceClickObserver
              )
            case (Some(position), colIndex) =>
              gameState.piecesFromPosition.get(position) match {
                case None =>
                  td(
                    isBlackSquare(rowIndex, colIndex),
                    className := withBorderCls,
                    blanks((rowIndex, colIndex)),
                    onMouseEnter.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                    onMouseLeave.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                    onClick.mapTo(None) --> pieceClickObserver
                  )
                case Some(piece) =>
                  td(
                    isBlackSquare(rowIndex, colIndex),
                    className := withBorderCls,
                    images(piece),
                    onMouseEnter.mapTo(Some(piece)) --> hoveredPieceObserver,
                    onMouseLeave.mapTo(None) --> hoveredPieceObserver,
                    onClick.mapTo(Some(piece)) --> pieceClickObserver,
                    className <-- maybeSelectedPieceSignal
                      .map(_.contains(piece))
                      .map(pieceIsSelected =>
                        if pieceIsSelected then s"piece-selected-${piece.team.toString.toLowerCase}" else ""
                      )
                  )
              }
          })
        )
      }

    val colNames = boundaries.alphabet.map(char => th(char.toUpper.toString)).take(boundaries.lastCol).toList

    table(
      className := "game-state-table",
      thead(tr(th(), symmetry(colNames))),
      tbody(symmetry(rows))
    )
  }
