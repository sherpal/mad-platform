package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*
import be.doeraene.mad.game.GameAction.*
import be.doeraene.mad.game.Positions.*

/** The bijection between [[GameAction]] and the `0 until size` range that a network's policy head is indexed by.
  *
  * [[GameAction.allActions]] is already a fixed list of every action the game has - actions name the piece they move
  * rather than the square it starts from, so unlike in chess the list does not depend on the position - which makes it
  * a natural policy head layout. Two things stand in the way of just using `indexOf`:
  *
  *   - [[GameAction.GamePieceMoves2]] is a case class holding an `Array` (`alternativePaths`), so its generated
  *     `equals` compares that field by reference. A structurally identical action rebuilt from, say, a decoded replay
  *     would not equal the canonical one in `allActions`, and `indexOf` would return -1.
  *   - scanning a 306-entry array for every node of a search tree is wasteful.
  *
  * So every action is reduced to a [[Long]] key built only from pinned indices (see [[GamePiece.orderedPieces]]) and
  * looked up in a map. The key is injective but deliberately not decodable: to go back, index into `allActions`.
  */
object ActionIndex:

  /** Size of the full policy space, over both teams. */
  val size: Int = GameAction.allActions.length

  /** Size of the policy space of a single team, which is what a network canonicalised to "red is always to move"
    * actually needs. See [[Canonical.policyIndex]].
    */
  val teamSize: Int = GameAction.redActions.length

  private inline def pieceIdx(piece: GamePiece): Long = GamePiece.pieceIndex(piece).toLong

  private val directionIdx: Map[Direction, Long] =
    Positions.directions.toVector.zipWithIndex.map((direction, index) => direction -> index.toLong).toMap

  /** The square a two-step move lands on, relative to where it started. This is exactly what `twoMovements` groups its
    * paths by, so it identifies the action within its piece.
    */
  private def deltaOf(action: GamePieceMoves2): (Int, Int) =
    (action.firstDirection.x + action.secondDirection.x, action.firstDirection.y + action.secondDirection.y)

  private val deltaIdx: Map[(Int, Int), Long] =
    GameAction.twoMovements.toVector.map(deltaOf).distinct.zipWithIndex.map { (delta, index) =>
      delta -> index.toLong
    }.toMap

  /* Key builders, one per action kind. Split out from `keyOf` because `mirroredKey` needs to build the key of an action
   * it cannot construct: mirroring a GamePieceMoves2 would mean rebuilding its `alternativePaths`. */
  private def identityKey(team: Team): Long = 0L | ((team.value: Int).toLong << 3)
  private def moves1Key(piece: GamePiece, direction: Direction): Long =
    1L | (pieceIdx(piece) << 3) | (directionIdx(direction) << 7)
  private def moves2Key(piece: GamePiece, delta: (Int, Int)): Long =
    2L | (pieceIdx(piece) << 3) | (deltaIdx(delta) << 7)
  private def permutationKey(piece1: GamePiece, piece2: GamePiece): Long =
    3L | (pieceIdx(piece1) << 3) | (pieceIdx(piece2) << 7)
  private def rotationKey(piece1: GamePiece, piece2: GamePiece, piece3: GamePiece): Long =
    4L | (pieceIdx(piece1) << 3) | (pieceIdx(piece2) << 7) | (pieceIdx(piece3) << 11)
  private def lastRowBonusKey(movementKey: Long, shiftKey: Long): Long =
    5L | (movementKey << 3) | (shiftKey << 24)

  private def keyOf(action: GameAction): Long = action match
    case action: Identity        => identityKey(action.actionForTeam)
    case action: GamePieceMoves1 => moves1Key(action.piece, action.direction)
    case action: GamePieceMoves2 => moves2Key(action.piece, deltaOf(action))
    case action: Permutation     => permutationKey(action.piece1, action.piece2)
    case action: Rotation        => rotationKey(action.piece1, action.piece2, action.piece3)
    case action: LastRowBonus    => lastRowBonusKey(keyOf(action.movement1), keyOf(action.shiftAction))

  /** The key of this action's image under the vertical mirror, without building that action.
    *
    * Mirroring reflects rows and swaps teams, so a piece becomes its [[GamePiece.otherTeamCounterparts]] counterpart
    * and a displacement `(dRow, dCol)` becomes `(-dRow, dCol)`.
    */
  private def mirroredKey(action: GameAction): Long = action match
    case action: Identity        => identityKey(action.actionForTeam.otherTeam)
    case action: GamePieceMoves1 => moves1Key(counterpart(action.piece), mirrorDirection(action.direction))
    case action: GamePieceMoves2 =>
      val (deltaRow, deltaCol) = deltaOf(action)
      moves2Key(counterpart(action.piece), (-deltaRow, deltaCol))
    case action: Permutation => permutationKey(counterpart(action.piece1), counterpart(action.piece2))
    case action: Rotation =>
      rotationKey(counterpart(action.piece1), counterpart(action.piece2), counterpart(action.piece3))
    case action: LastRowBonus => lastRowBonusKey(mirroredKey(action.movement1), mirroredKey(action.shiftAction))

  private inline def counterpart(piece: GamePiece): GamePiece = GamePiece.otherTeamCounterparts(piece)

  private def mirrorDirection(direction: Direction): Direction =
    if direction == Positions.top then Positions.bottom
    else if direction == Positions.bottom then Positions.top
    else direction

  private val indexByKey: Map[Long, Int] =
    GameAction.allActions.toVector.map(keyOf).zipWithIndex.toMap

  /** Position of this action in [[GameAction.allActions]]. Works on any structurally valid action, not only on the
    * canonical instances handed out by [[GameState.allValidActions]].
    */
  def indexOf(action: GameAction): Int = indexByKey(keyOf(action))

  /** Inverse of [[indexOf]]. Always returns the canonical instance, which is what makes the result safe to compare with
    * `==` and to hand to [[GameAction.act]].
    */
  def fromIndex(index: Int): GameAction = GameAction.allActions(index)

  /** Permutation of the index space induced by the vertical mirror, precomputed so that mirroring an action at search
    * time is one array read.
    */
  val mirroredIndices: Array[Int] =
    GameAction.allActions.map(action => indexByKey(mirroredKey(action)))

  /** The same action played by the other team on a vertically mirrored board. */
  def mirror(action: GameAction): GameAction = fromIndex(mirroredIndices(indexOf(action)))

  /** Index of an action within its own team's action list, and back.
    *
    * A canonicalised network only ever sees red to move, so its policy head has [[teamSize]] outputs rather than
    * [[size]].
    */
  val teamIndices: Array[Int] = {
    val indices  = Array.fill(size)(-1)
    var redRank  = 0
    var blueRank = 0
    var index    = 0
    while index < size do
      if GameAction.allActions(index).actionForTeam == Team.Red then
        indices(index) = redRank
        redRank += 1
      else
        indices(index) = blueRank
        blueRank += 1
      index += 1
    indices
  }

  /** Inverse of [[teamIndices]] for red, the team a canonicalised state is always to move for. */
  val redIndices: Array[Int] =
    GameAction.allActions.zipWithIndex.collect { case (action, index) if action.actionForTeam == Team.Red => index }

  /** A stable human-readable name, built only from pinned indices.
    *
    * Unlike [[GameAction.prettyPrint]] this needs no [[GameState]], which is what lets `PinnedOrderSpecs` fingerprint
    * the whole ordering and fail loudly if anything ever permutes it.
    */
  /** Hash of the whole ordering, as the concatenated [[descriptor]]s of [[GameAction.allActions]].
    *
    * Stamped into a training set's manifest and asserted by `PinnedOrderSpecs`, so a model and the data it was trained
    * on can both be tied to the layout they assume. `String.hashCode` is specified by the JDK and matched by Scala.js,
    * so this is the same number on both platforms the game compiles to.
    */
  lazy val orderingFingerprint: Int =
    GameAction.allActions.toVector.map(descriptor).mkString("\n").hashCode

  def descriptor(action: GameAction): String = action match
    case action: Identity        => s"Pass(${action.actionForTeam.prettyPrint})"
    case action: GamePieceMoves1 => s"Move1(${action.piece.prettyPrint},${action.direction.x},${action.direction.y})"
    case action: GamePieceMoves2 =>
      val (deltaRow, deltaCol) = deltaOf(action)
      s"Move2(${action.piece.prettyPrint},$deltaRow,$deltaCol)"
    case action: Permutation => s"Perm(${action.piece1.prettyPrint},${action.piece2.prettyPrint})"
    case action: Rotation =>
      s"Rot(${action.piece1.prettyPrint},${action.piece2.prettyPrint},${action.piece3.prettyPrint})"
    case action: LastRowBonus => s"Bonus(${descriptor(action.movement1)},${descriptor(action.shiftAction)})"

end ActionIndex
