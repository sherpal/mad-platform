package be.doeraene.mad.game

import Positions.*
import GamePiece.*
import be.doeraene.perf.NatArray

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

  /** The same position seen from the other side: every piece is reflected across the horizontal mid-line and handed to
    * the other team, and it becomes the other team's turn.
    *
    * All four [[GameBoundaries]] have vertically symmetric shapes *and* point-reflected starting positions, so this is
    * a genuine symmetry of the game. It is what lets a network be trained on a single point of view (see
    * [[be.doeraene.mad.ai.nn.Canonical]]), and it doubles as free data augmentation. The property that matters is that
    * it commutes with playing: for a legal `action`,
    * {{{
    *   action(this).mirrored == ActionIndex.mirror(action)(this.mirrored)
    * }}}
    *
    * [[turnNumber]] therefore has to shift by exactly one - the mirrored game is the same game with the colours
    * swapped, which is to say the game where the other team moved first - and not, say, swap within its pair. That
    * makes the shift the one thing about this that is not an involution: `mirrored.mirrored` has the same pieces and
    * the same team to move as `this`, with [[turnNumber]] two higher.
    *
    * It also leaves the opening plies inexact under [[withInitialSpecialRule]]. At `turnNumber == 2` the mover has yet
    * to play, but its mirror image lands on turn 3, where [[teamAlreadyPlayed]] says it has - and "nobody has moved
    * yet" simply is not the colour-swap of "one side has". Mirroring twice shifts by two, which breaks turn 1 the same
    * way. Every later turn mirrors faithfully, and so does every turn at all once the special rule is off;
    * [[be.doeraene.mad.ai.nn.Canonical.mirrorIsExact]] is that condition.
    */
  lazy val mirrored: GameState = copy(
    pieces = pieces.map((piece, position) =>
      GamePiece.otherTeamCounterparts(piece) -> gameBoundaries.mirrorVertically(position)
    ),
    turnNumber = turnNumber + 1
  )

  /** Returns the winning [[Team]], if any. */
  def maybeWinner: Option[Team] = (pieceIsAlive(blue111), pieceIsAlive(red111)) match {
    case (true, false) => Some(Team.Blue)
    case (false, true) => Some(Team.Red)
    case _             => None
  }

  /** Returns whether the game has ended. */
  def ended: Boolean = turnsSinceLastPieceDied >= GameState.drawAfterTurnsWithoutDeath || maybeWinner.isDefined

  /** Returns the list of valid [[GameAction]] given this [[GameState]]. */
  def allValidActions: NatArray[GameAction] = turnOfTeam match {
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

  def applyAllActions(actions: Iterable[GameAction]): GameState =
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

  /** The game is a draw once this many turns have gone by without a piece dying. */
  val drawAfterTurnsWithoutDeath: Int = 30

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
