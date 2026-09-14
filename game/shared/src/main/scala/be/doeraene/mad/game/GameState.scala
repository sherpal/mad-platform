package be.doeraene.mad.game

import Positions.*
import GamePiece.*

/** Represents the board of the Mad game.
  *
  * @param pieces
  *   map from all alive pieces to their position on the game
  * @param turnNumber
  *   turn number. Starts at 1, increase after each action
  * @param turnsSinceLastPieceDied
  *   number of turns since the last piece was killed.
  * @param withInitialSpecialRule
  *   When activated, people can't move on their first turn, but can only do a rotation, permutation, or chose to do
  *   nothing.
  */
final class GameState(
    val gameBoundaries: GameBoundaries
)(
    val pieces: Map[GamePiece, gameBoundaries.Position],
    val turnNumber: Int,
    val turnsSinceLastPieceDied: Int,
    val withInitialSpecialRule: Boolean
):

  def gameType: GameBoundaries.GameType = gameBoundaries.gameType

  override def equals(that: Any): Boolean = that match {
    case that: GameState =>
      this.turnNumber == that.turnNumber &&
      this.turnsSinceLastPieceDied == that.turnsSinceLastPieceDied &&
      this.pieces == that.pieces && this.gameBoundaries == that.gameBoundaries
    case _ => false
  }

  def shape: (Int, Int) = (gameBoundaries.lastRow, gameBoundaries.lastCol)

  def copy(
      pieces: Map[GamePiece, gameBoundaries.Position] = pieces,
      turnNumber: Int = turnNumber,
      turnsSinceLastPieceDied: Int = turnsSinceLastPieceDied,
      withInitialSpecialRule: Boolean = withInitialSpecialRule
  ): GameState =
    GameState(gameBoundaries)(pieces, turnNumber, turnsSinceLastPieceDied, withInitialSpecialRule)

  def setInitialSpecialRule(withRule: Boolean): GameState =
    copy(withInitialSpecialRule = withRule)

  type Position = gameBoundaries.Position

  lazy val piecesFromPosition: Map[Position, GamePiece] = pieces.map(_.swap)

  def prettyPrint: String = {
    val columnSize = 8

    val header = "  " ++ "A".padTo(columnSize, ' ') ++ "B".padTo(columnSize, ' ') ++ "C".padTo(columnSize, ' ') ++ "D"
      .padTo(columnSize, ' ') ++ "\n"

    header ++ gameBoundaries.allPositions
      .map(piecesFromPosition.get)
      .grouped(4)
      .zipWithIndex
      .map { (row, index) =>
        (6 - index).toString ++ " " ++ row
          .map(_.map(_.prettyPrint.padTo(columnSize, ' ')).getOrElse("".padTo(columnSize, ' ')))
          .mkString("")
      }
      .mkString("\n")
  }

  /** Returns whether this piece is present on the board (hence, not in exile zone). */
  def pieceIsAlive(gamePiece: GamePiece): Boolean = pieces.isDefinedAt(gamePiece)

  /** Returns maybe the current position of this piece. */
  def maybePiecePosition(piece: GamePiece): Option[Position] = pieces.get(piece)

  /** Returns whether the [[Team.Blue]] team already played. */
  def bluePlayed: Boolean = turnNumber > 2

  /** Returns whether the [[Team.Red]] team already played. */
  def redPlayed: Boolean = turnNumber > 1

  def teamAlreadyPlayed(team: Team): Boolean = if (team == Team.Blue) bluePlayed else redPlayed

  /** Returns which team should play now. */
  def turnOfTeam: Team = if turnNumber % 2 == 0 then Team.Blue else Team.Red

  /** Returns the winning [[Team]], if any. */
  def maybeWinner: Option[Team] = (pieceIsAlive(blue111), pieceIsAlive(red111)) match {
    case (true, false) => Some(Team.Blue)
    case (false, true) => Some(Team.Red)
    case _             => None
  }

  /** Returns whether the game has ended. */
  def ended: Boolean = turnsSinceLastPieceDied >= 30 || maybeWinner.isDefined

  /** Returns the list of valid [[GameAction]] given this [[GameState]]. */
  def allValidActions: List[GameAction] = turnOfTeam match {
    case Team.Blue => GameAction.blueActions.filter(_.isLegal(this))
    case Team.Red  => GameAction.redActions.filter(_.isLegal(this))
  }

  /** Returns the number of pieces in the game. */
  def pieceCount: Int = pieces.size

  /** Returns the center of mass of the blue pieces, and the red pieces. The mass of a piece is defined as 1 for all
    * pieces but for 111, for which is 8 - (n - 1), n being the number of pieces.
    */
  lazy val centersOfMass: ((Double, Double), (Double, Double)) = {
    val (bluePieces, redPieces) = pieces.keys.toList.partition(_.team == Team.Blue)

    def centerOfMassOf(thesePieces: List[GamePiece]): (Double, Double) = {
      val _111Mass = 9.0 - thesePieces.length
      val piecesMassAndPosition = thesePieces.map { piece =>
        val piecePosition = pieces(piece).asDoublePair
        val mass          = if piece.is111 then _111Mass else 1.0
        (piecePosition, mass)
      }
      val partitionFunction = piecesMassAndPosition.map(_._2).sum
      val weightedPositionSum = piecesMassAndPosition.foldLeft((0.0, 0.0)) { case ((accX, accY), (position, mass)) =>
        (
          accX + position._1 * mass,
          accY + position._2 * mass
        )
      }
      (
        weightedPositionSum._1 / partitionFunction,
        weightedPositionSum._2 / partitionFunction
      )
    }

    (centerOfMassOf(bluePieces), centerOfMassOf(redPieces))
  }

  def applyAllActions(actions: List[GameAction]): GameState =
    actions.foldLeft(this)((gs, a) => a(gs))

  lazy val (redScore, blueScore) = {
    val (redPieces, bluePieces) = pieces.keys.partition(_.team == Team.Red)

    val redScore  = redPieces.map(_.pieceValue).sum
    val blueScore = bluePieces.map(_.pieceValue).sum
    (redScore, blueScore)
  }

  def materialScore(team: Team): Double = team match {
    case Team.Red  => redScore
    case Team.Blue => blueScore
  }

end GameState

object GameState:

  type AnyGameState = GameState

  def initial6By4GameState(withInitialSpecialRule: Boolean): GameState =
    initialGameStateWithBoundaries(GameBoundaries.OriginalSixByFour(), withInitialSpecialRule)

  def initial5By5GameState(withInitialSpecialRule: Boolean): GameState =
    initialGameStateWithBoundaries(GameBoundaries.DefaultFiveByFive(), withInitialSpecialRule)

  def initialGameStateWithBoundaries(boundaries: GameBoundaries, withInitialSpecialRule: Boolean): GameState =
    GameState(boundaries)(boundaries.startingPositions, 1, 0, withInitialSpecialRule)

  def initialGameStateWithShape(
      shape: (Int, Int),
      withInitialSpecialRule: Boolean
  ): GameState =
    if shape == (6, 4) then initial6By4GameState(withInitialSpecialRule)
    else if shape == (5, 5) then initial5By5GameState(withInitialSpecialRule)
    else throw new IllegalArgumentException(s"The given shape $shape is not allowed currently.")
