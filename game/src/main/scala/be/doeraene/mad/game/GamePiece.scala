package be.doeraene.mad.game

import GamePiece.*
import be.doeraene.perf.NatArray

/** A [[GamePiece]] is a pawn on the game. There are 16 in total (8 per team), characterised by a [[Movement]], an
  * [[Attack]] and a [[Defence]].
  */
final case class GamePiece(movement: Movement, attack: Attack, defence: Defence, team: Team) {

  lazy val opponent111: GamePiece = team.otherTeam._111

  def prettyPrint: String = s"$team$movement$attack$defence"

  /** Returns whether this [[GamePiece]] can take that other [[GamePiece]], regardless of positions. */
  def canTake(that: GamePiece): Boolean = this.attack >= that.defence && this.team != that.team

  /** Returns the number of opponent pieces this piece cann take, given this [[GameState]] */
  def pieceTakeScore(gameState: GameState): Double =
    GameAction.movementsByPiece
      .getOrElse(this, NatArray.empty[GameAction.MovementAction])
      .count(_.doesSomeoneDie(gameState))
      .toDouble

  /** Returns the number of opponent pieces can take this piece, given this [[GameState]].
    *
    * Only scans the movements of opponent pieces actually on the board: a dead piece's `finalPosition` is always `None`
    * (see [[GameAction.MovementAction.finalPosition]]), so it could never have matched anyway - this just skips
    * guaranteed-empty work instead of scanning all ~128 [[GameAction.allMovements]] regardless of how many pieces
    * remain, which used to cost the same whether 16 pieces were still alive or 3.
    */
  def piecesTakenScore(gameState: GameState): Double = gameState.pieces.get(this) match {
    case Some(myPosition) =>
      NatArray
        .from(gameState.pieces.keys)
        .filter(_.team != team)
        .flatMap(GameAction.movementsByPiece.getOrElse(_, NatArray.empty[GameAction.MovementAction]))
        .count(_.finalPosition(gameState).contains(myPosition))
        .toDouble
    case None => Double.MinValue
  }

  /** Returns how many turn this piece would take to go to that other [[GamePiece]] */
  def turnToGoTo(that: GamePiece, gameState: GameState): Double =
    movement.turnToTravel(manhattanDistanceTo(that, gameState))

  def turnToGoTo(thatPosition: (Double, Double), gameState: GameState): Double = (for {
    thisPosition <- gameState.maybePiecePosition(this)
    (thisX, thisY) = thisPosition.asDoublePair
    (thatX, thatY) = thatPosition
  } yield movement.turnToTravel(math.abs(thisX - thatX) + math.abs(thisY - thatY)))
    .getOrElse(20.0)

  def turnToGoToOpponent111(gameState: GameState): Double = turnToGoTo(opponent111, gameState)

  private def manhattanDistanceTo(that: GamePiece, gameState: GameState): Int = (for {
    thisPosition <- gameState.maybePiecePosition(this)
    thatPosition <- gameState.maybePiecePosition(that)
    distance = thisPosition.distanceTo(thatPosition)
  } yield distance).getOrElse(24)

  def manhattanDistanceToOpponent111(gameState: GameState): Int =
    manhattanDistanceTo(opponent111, gameState)

  def euclideanDistance(that: GamePiece, gameState: GameState): Double = (for {
    thisPosition <- gameState.maybePiecePosition(this)
    thatPosition <- gameState.maybePiecePosition(that)
    distance = thisPosition.euclideanDistanceTo(thatPosition)
  } yield distance).getOrElse(7)

  def euclideanDistanceToOpponent111(gameState: GameState): Double =
    euclideanDistance(opponent111, gameState)

  def statValues: (Int, Int, Int) = GamePiece.value(movement, attack, defence)

  def is111: Boolean = isMAD[1, 1, 1]

  def is222: Boolean = isMAD[2, 2, 2]

  def isMAD[M <: GamePiece.PieceStat: ValueOf, A <: GamePiece.PieceStat: ValueOf, D <: GamePiece.PieceStat: ValueOf]
      : Boolean =
    statValues == (valueOf[M], valueOf[A], valueOf[D])

  lazy val pieceValue: Double =
    if is222 then 3.9
    else {
      val (m, a, d) = statValues
      math.sqrt(m * a * d)
    }
}

object GamePiece:
  type PieceStat = 1 | 2

  opaque type Movement <: Int = PieceStat
  inline def movementValue(movement: Movement): Int = movement
  val movement1: Movement                           = 1
  val movement2: Movement                           = 2
  object Movement:
    extension (movement: Movement) def turnToTravel(distance: Double): Double = distance / movement.toDouble

  opaque type Attack <: Int = PieceStat
  inline private def attackValue(attack: Attack): Int = attack
  val attack1: Attack                                 = 1
  object Attack:
    extension (attack: Attack)
      def >=(defence: Defence): Boolean = attack >= defence
      def >(defence: Defence): Boolean  = attack > defence

    given Ordering[Attack] = (x: Attack, y: Attack) => attackValue(x) compare attackValue(y)

  opaque type Defence <: Int = PieceStat
  inline def defenceValue(defence: Defence): Int = defence

  def value(movement: Movement, attack: Attack, defence: Defence): (Int, Int, Int) = (movement, attack, defence)

  val blue111 = GamePiece(1, 1, 1, Team.Blue)
  val blue112 = GamePiece(1, 1, 2, Team.Blue)
  val blue121 = GamePiece(1, 2, 1, Team.Blue)
  val blue211 = GamePiece(2, 1, 1, Team.Blue)
  val blue122 = GamePiece(1, 2, 2, Team.Blue)
  val blue212 = GamePiece(2, 1, 2, Team.Blue)
  val blue221 = GamePiece(2, 2, 1, Team.Blue)
  val blue222 = GamePiece(2, 2, 2, Team.Blue)
  val red111  = GamePiece(1, 1, 1, Team.Red)
  val red112  = GamePiece(1, 1, 2, Team.Red)
  val red121  = GamePiece(1, 2, 1, Team.Red)
  val red211  = GamePiece(2, 1, 1, Team.Red)
  val red122  = GamePiece(1, 2, 2, Team.Red)
  val red212  = GamePiece(2, 1, 2, Team.Red)
  val red221  = GamePiece(2, 2, 1, Team.Red)
  val red222  = GamePiece(2, 2, 2, Team.Red)

  val pieces: Set[GamePiece] = Set(
    blue111,
    blue112,
    blue121,
    blue211,
    blue122,
    blue212,
    blue221,
    blue222,
    red111,
    red112,
    red121,
    red211,
    red122,
    red212,
    red221,
    red222
  )

  val piecesByIndex: Map[Int, GamePiece] = pieces.zipWithIndex.map(_.swap).toMap

  val oppositePieces: Map[GamePiece, GamePiece] = Map(
    blue111 -> blue222,
    blue112 -> blue221,
    blue121 -> blue212,
    blue211 -> blue122,
    blue122 -> blue211,
    blue212 -> blue121,
    blue221 -> blue112,
    blue222 -> blue111,
    red111  -> red222,
    red112  -> red221,
    red121  -> red212,
    red211  -> red122,
    red122  -> red211,
    red212  -> red121,
    red221  -> red112,
    red222  -> red111
  )

  val rotationPools: NatArray[Set[GamePiece]] = NatArray(
    Set(blue112, blue121, blue211),
    Set(blue122, blue212, blue221),
    Set(red112, red121, red211),
    Set(red122, red212, red221)
  )

  def fromPrettyPrint(prettyPrint: String): Option[GamePiece] = pieces.find(_.prettyPrint == prettyPrint)
