package be.doeraene.mad.ai

import be.doeraene.mad.game.GameAction.GamePieceMoves2
import be.doeraene.mad.game.{GameAction, GamePiece, GameState, Team}
import be.doeraene.perf.NatArray

/** A [[GameState]] evaluator built around the one thing a shallow minimax cannot see for itself: what is *about* to be
  * captured at the leaf it stops on.
  *
  * At depth 3 the search stops four plies in, statically scores the position and believes the number. Every evaluator
  * that only counts what is on the board therefore happily walks a destroyer onto a square where it is taken for free
  * one ply past the horizon - the material is all still there in the picture it was shown. [[ClaudeEvaluator]] patches
  * this for exactly two pieces, 111 and 222, via [[GamePiece.piecesTakenScore]]; every other ship is evaluated as if
  * nothing on the board could touch it.
  *
  * So the centre of this evaluator is a single cheap board scan ([[Scan]]) that answers, for *every* ship at once, "who
  * can land on this square, and what happens if they do":
  *
  *   - [[Scorer.hangingScore]] resolves each threat as an exchange rather than a loss: a destroyer attacked by a ship
  *     that can be taken straight back costs the difference, not its whole value. This is what stops the evaluator from
  *     panicking about pieces that are perfectly well covered, and what lets it see that a trade it *can* take is good.
  *   - it is tempo-aware. Only one ship can be saved per turn and only one capture can be executed per turn, so the
  *     side to move discounts its own worst threat (it gets to answer it) and fully counts its opponent's. Without this
  *     the same position scores identically whoever is on move, which is exactly wrong in a game where almost every
  *     piece can be captured by something.
  *   - [[Scorer.corvetteDanger]] reuses the same scan for 111, where the currency isn't material at all: a threatened
  *     111 with the opponent on move is a lost game, and a 111 whose escape squares are all covered is lost a move
  *     later. Counting *safe* escapes rather than legal ones is the difference between seeing a trap and walking into
  *     it.
  *
  * Around that core sit the terms that give the thing a plan rather than just a survival instinct: a material scale
  * where every ship is worth something strictly positive (see [[TacticalWeights]]), recall credit modelling what
  * permutation and rotation can actually *buy* back, a hunt pull toward the enemy 111, mobility, centre, and a draw
  * term so a winning position doesn't drift into the 30-turn tie-break.
  *
  * All constants live in [[TacticalWeights]]. The known 111-vs-111 ending is settled upstream by
  * [[be.doeraene.mad.ai.minimax.TreeExplorer.queenVSQueenSituation]], so it is not this evaluator's problem.
  */
object TacticalEvaluator:

  /** Squares are indexed `row * 8 + col` into flat arrays rather than kept as `(Int, Int)` tuples in a `Map`. This runs
    * at every leaf of every search, and a tuple-keyed `Map` would box two `Int`s and hash them for every single square
    * lookup; both boards in play (6x4 and 5x5) have fewer than 8 columns, so the packed index is exact.
    */
  private inline val SquareCount = 64

  private val allPieces: NatArray[GamePiece] =
    NatArray.from(Array.tabulate(GamePiece.pieces.size)(GamePiece.piecesByIndex))
  private val indexOfPiece: Map[GamePiece, Int] = GamePiece.piecesByIndex.map(_.swap)
  private val pieceCount: Int                   = allPieces.length

  /** Per-piece constants that don't depend on the position, resolved once instead of at every node: the opaque
    * `movement`/`attack`/`defence` accessors, the `canTake` matrix and the team split all end up in the inner loops of
    * a search that visits tens of thousands of nodes per move.
    */
  private val isCorvette: NatArray[Boolean] = allPieces.map(_.is111)
  private val isRed: NatArray[Boolean]      = allPieces.map(_.team == Team.Red)
  private val isFast: NatArray[Boolean]     = allPieces.map(_.movement >= 2)
  private val movesOf: NatArray[NatArray[GameAction.MovementAction]] =
    allPieces.map(GameAction.movementsByPiece.getOrElse(_, NatArray.empty))

  /** `canTake(a)(b)`: piece `a` may land on a square held by piece `b`, ie. they are enemies and `a` outguns `b`. */
  private val canTake: NatArray[NatArray[Boolean]] =
    allPieces.map(attacker => allPieces.map(attacker.canTake))

  /** The complement of each piece (permutation partner) and the rotation pools, as indices. */
  private val complementOf: NatArray[Int] = allPieces.map(piece => indexOfPiece(GamePiece.oppositePieces(piece)))
  private val redCorvette: Int            = indexOfPiece(GamePiece.red111)
  private val blueCorvette: Int           = indexOfPiece(GamePiece.blue111)
  private val rotationPools: NatArray[NatArray[Int]] = GamePiece.rotationPools.map(NatArray.from(_).map(indexOfPiece))

  /** One board scan, shared by every term that needs to know who attacks what.
    *
    * Everything here is an array indexed by piece index or by packed square, allocated fresh per evaluation and never
    * escaping it - the previous `Map`-based version of this was measurably the most expensive thing in the search.
    */
  private final class Scan(gameState: GameState):
    /** packed square each piece stands on, or -1 if it is exiled */
    val squareOf: NatArray[Int] = NatArray.fill(pieceCount)(-1)

    /** index of the piece standing on each square, or -1 if empty */
    val occupantOf: NatArray[Int] = NatArray.fill(SquareCount)(-1)

    /** every square a piece could land on, *ignoring* who stands there - see [[reachableSquares]] */
    val landingsOf: NatArray[NatArray[Int]] = NatArray.fill(pieceCount)(NatArray.empty[Int])

    /** every piece that could land on a square, the reverse index of [[landingsOf]] */
    val reachersOf: NatArray[NatArray[Int]] = NatArray.fill(SquareCount)(NatArray.empty[Int])

    val alive: Array[Int] =
      val builder = Array.newBuilder[Int]
      gameState.pieces.foreach { (piece, position) =>
        val index      = indexOfPiece(piece)
        val (row, col) = position.asPair
        val square     = row * 8 + col
        squareOf(index) = square
        occupantOf(square) = index
        builder += index
      }
      builder.result()

    alive.foreach { index =>
      val landings = reachableSquares(gameState, index)
      landingsOf(index) = landings
      landings.foreach(square => reachersOf(square) = reachersOf(square).prepended(index))
    }

    /** Whether `piece` could actually move onto `square` right now: it is empty, or holds an opponent it outguns. */
    def landingIsLegal(piece: Int, square: Int): Boolean =
      val there = occupantOf(square)
      there < 0 || canTake(piece)(there)

    def isEnemyOf(piece: Int, other: Int): Boolean = isRed(piece) != isRed(other)
  end Scan

  /** Every square this ship could land on, ignoring who is standing there.
    *
    * Deliberately *not* [[GameAction.MovementAction.isLegal]]: that also asks whose turn it is (so it would report
    * nothing at all for the side not on move, which is precisely the side whose threats matter most) and that the
    * arrival square is takeable. Squares occupied by our own ships have to stay in this set, because the question asked
    * of it downstream is "could this ship recapture *after* the piece standing there is gone".
    */
  private def reachableSquares(gameState: GameState, piece: Int): NatArray[Int] =
    def packed(position: gameState.Position): Int =
      val (row, col) = position.asPair
      row * 8 + col

    movesOf(piece).flatMap {
      case twoSquares: GamePieceMoves2 =>
        if twoSquares.existsEmptyFirstPosition(gameState) then twoSquares.finalPosition(gameState).map(packed)
        else None
      case oneSquare => oneSquare.finalPosition(gameState).map(packed)
    }

  /** Holds one set of [[TacticalWeights]] together with everything derivable from it, so the per-weights tables (the
    * material scale, most of all) are built once per player rather than once per evaluated node.
    */
  final class Scorer(weights: TacticalWeights):

    /** Material value of a ship on the board. 111 is worth nothing here on purpose: it is never traded, its whole
      * importance is the terminal condition and [[corvetteDanger]].
      */
    private val valueOfPiece: NatArray[Double] = allPieces.map { piece =>
      if piece.is111 then 0.0
      else
        val (movement, attack, defence) = piece.statValues
        weights.materialBase + weights.attackBonus * (attack - 1) + weights.defenceBonus * (defence - 1) +
          weights.movementBonus * (movement - 1) + weights.dualBonus * (attack - 1) * (defence - 1)
    }

    /** What an exiled ship is still worth, expressed as what recalling it would actually *buy*.
      *
      * Crediting an exiled ship with a fraction of its own value, as if it were a slightly worse version of being
      * alive, misreads the rule: a recall is never a gain of a ship, it is a *swap*. A permutation brings 122 back only
      * by exiling 211; a rotation brings a frigate back only by exiling one of the two still in the arena. So the value
      * of the whole recall option is the best single upgrade available - the exiled ship you'd want minus the one you'd
      * have to give up - and it's one option, not one per exiled ship, because it costs the turn either way.
      *
      * 222 is the exception the rulebook itself calls out: its complement is 111, so "recalling" it means exiling your
      * own corvette and losing on the spot. Once exiled it is simply gone.
      */
    private def recallCredit(scan: Scan, teamIsRed: Boolean): Double =
      var bestUpgrade = 0.0
      var index       = 0
      while index < pieceCount do
        if isRed(index) == teamIsRed && scan.squareOf(index) < 0 && !isCorvette(index) then
          val partner = complementOf(index)
          // an exiled 222 has 111 as its partner, and "recalling" it would exile our own corvette: never an option
          if !isCorvette(partner) && scan.squareOf(partner) >= 0 then
            bestUpgrade = math.max(bestUpgrade, valueOfPiece(index) - valueOfPiece(partner))
        index += 1

      rotationPools.foreach { pool =>
        if isRed(pool(0)) == teamIsRed then
          var aliveCount = 0
          var bestExiled = Double.MinValue
          var worstAlive = Double.MaxValue
          pool.foreach { member =>
            if scan.squareOf(member) >= 0 then
              aliveCount += 1
              worstAlive = math.min(worstAlive, valueOfPiece(member))
            else bestExiled = math.max(bestExiled, valueOfPiece(member))
          }
          // with two of a pool in the arena, the third can be cycled back in for either of them
          if aliveCount >= 2 && bestExiled > Double.MinValue then
            bestUpgrade = math.max(bestUpgrade, bestExiled - worstAlive)
      }

      weights.recallCredit * bestUpgrade

    private def materialScore(scan: Scan, teamIsRed: Boolean): Double =
      var total = 0.0
      scan.alive.foreach(index => if isRed(index) == teamIsRed then total += valueOfPiece(index))
      total + recallCredit(scan, teamIsRed)

    /** What the best capture against `piece` actually costs us, treating it as an exchange rather than a loss.
      *
      * A ship covered by a friend that can take the capturer straight back is not hanging; it is offering a trade, and
      * the trade only costs the difference in value. The attacker naturally picks whichever of its options nets it the
      * most, so this maxes over the attackers rather than averaging or taking the first.
      */
    private def exchangeLoss(scan: Scan, piece: Int): Double =
      val square   = scan.squareOf(piece)
      val onSquare = scan.reachersOf(square)
      var worst    = 0.0
      onSquare.foreach { attacker =>
        if canTake(attacker)(piece) then
          val recaptured = onSquare.exists(defender =>
            defender != piece && !scan.isEnemyOf(defender, piece) && canTake(defender)(attacker)
          )
          val loss = valueOfPiece(piece) - (if recaptured then valueOfPiece(attacker) else 0.0)
          if loss > worst then worst = loss
      }
      worst

    /** Aggregates one side's hanging ships into a single number, respecting that a turn only buys one move.
      *
      * Summing every threatened ship's value would double-count wildly: the opponent gets to execute exactly one of
      * those captures. So the worst threat counts fully and the next one only partially - and the side *on move* flips
      * that around, because it gets first say and can answer its own worst threat before it is ever executed.
      */
    private def hangingScore(scan: Scan, teamIsRed: Boolean, teamIsOnMove: Boolean): Double =
      var worst  = 0.0
      var second = 0.0
      scan.alive.foreach { index =>
        if isRed(index) == teamIsRed && !isCorvette(index) then
          val loss = exchangeLoss(scan, index)
          if loss > worst then
            second = worst
            worst = loss
          else if loss > second then second = loss
      }

      weights.hangingWeight *
        (if teamIsOnMove then weights.rescueFactor * worst + second else worst + weights.rescueFactor * second)

    /** How exposed this team's 111 is - in game-ending units, not material ones.
      *
      * Three distinct dangers, because they arrive on different timescales:
      *   - it is attacked right now. With the opponent on move that is simply the game; on our own move it is a threat
      *     we still have one turn to answer, hence [[TacticalWeights.corvetteTempoRelief]].
      *   - it has nowhere safe to go. Counting escape squares that are *covered by nothing* rather than merely legal is
      *     the whole point: a 111 with three legal moves onto three attacked squares is already lost, and an evaluator
      *     counting legal moves sees a comfortable position.
      *   - enemies are converging on it. A gradient rather than a binary, so a shallow search can steer away from
      *     danger several moves before it becomes a forced, too-late reaction - and, mirrored, so it can steer *toward*
      *     the enemy corvette.
      */
    private def corvetteDanger(scan: Scan, teamIsRed: Boolean, teamIsOnMove: Boolean): Double =
      val corvette = if teamIsRed then redCorvette else blueCorvette
      val square   = scan.squareOf(corvette)
      if square < 0 then 0.0 // terminal, settled upstream; defensive only
      else
        val row = square / 8
        val col = square % 8

        var attackers = 0
        scan.reachersOf(square).foreach(piece => if isRed(piece) != teamIsRed then attackers += 1)

        var safeEscapes = 0
        scan.landingsOf(corvette).foreach { target =>
          if scan.landingIsLegal(corvette, target) &&
            !scan.reachersOf(target).exists(piece => isRed(piece) != teamIsRed)
          then safeEscapes += 1
        }

        var approach   = 0.0
        var bodyguards = 0.0
        scan.alive.foreach { piece =>
          val distance = math.abs(scan.squareOf(piece) / 8 - row) + math.abs(scan.squareOf(piece) % 8 - col)
          if isRed(piece) != teamIsRed then
            val reach = if isFast(piece) then 2 else 1
            approach += math.max(0.0, (reach + 2) - distance)
          else if !isCorvette(piece) then bodyguards += math.max(0.0, 4 - distance)
        }

        val tempo = if teamIsOnMove then weights.corvetteTempoRelief else 1.0

        weights.corvetteAttackedWeight * tempo * attackers +
          weights.corvetteTrappedWeight * math.max(0.0, 2 - safeEscapes) +
          weights.corvetteApproachWeight * approach -
          weights.bodyguardWeight * bodyguards

    /** Pulls our ships toward the enemy corvette, so the evaluator has a plan to win and not only a plan to not lose.
      *
      * Phase-scaled: early on, with a full enemy fleet still able to counter-attack, sending escorts off hunting is how
      * our own 111 ends up alone a few moves later, so the pull starts weak and strengthens as the board thins out and
      * the game turns into a direct race.
      */
    private def huntPressure(scan: Scan, teamIsRed: Boolean, phaseMultiplier: Double): Double =
      val target = if teamIsRed then blueCorvette else redCorvette
      val square = scan.squareOf(target)
      if square < 0 then 0.0
      else
        val row   = square / 8
        val col   = square % 8
        var total = 0.0
        scan.alive.foreach { piece =>
          if isRed(piece) == teamIsRed && !isCorvette(piece) then
            val distance  = math.abs(scan.squareOf(piece) / 8 - row) + math.abs(scan.squareOf(piece) % 8 - col)
            val turnsToGo = if isFast(piece) then distance / 2.0 else distance.toDouble
            total += math.max(0.0, weights.huntHorizon - turnsToGo)
        }
        total * weights.huntWeight * phaseMultiplier

    /** Mobility and centre control, in one pass since both walk the same pieces. */
    private def spaceScore(
        scan: Scan,
        teamIsRed: Boolean,
        centerRow: Double,
        centerCol: Double,
        maxDistance: Double
    ): Double =
      var mobility = 0.0
      var center   = 0.0
      scan.alive.foreach { piece =>
        if isRed(piece) == teamIsRed then
          var legalLandings = 0
          scan.landingsOf(piece).foreach(target => if scan.landingIsLegal(piece, target) then legalLandings += 1)
          mobility += legalLandings

          val square        = scan.squareOf(piece)
          val proximity     = maxDistance - (math.abs(square / 8 - centerRow) + math.abs(square % 8 - centerCol))
          val mobilityBoost = if isFast(piece) then 1.5 else 1.0
          center += proximity * mobilityBoost
      }
      weights.mobilityWeight * mobility + weights.centerWeight * center

    def score(gameState: GameState, team: Team): Double =
      val scan        = new Scan(gameState)
      val weAreRed    = team == Team.Red
      val weAreOnMove = gameState.turnOfTeam == team

      val (lastRow, lastCol) = gameState.shape
      val centerRow          = (lastRow - 1) / 2.0
      val centerCol          = (lastCol - 1) / 2.0
      val maxDistance        = centerRow + centerCol
      val phaseMultiplier    = 1.5 - gameState.pieceCount / 16.0

      val material = materialScore(scan, weAreRed) - materialScore(scan, !weAreRed)
      val hanging  = hangingScore(scan, !weAreRed, !weAreOnMove) - hangingScore(scan, weAreRed, weAreOnMove)
      val corvette = corvetteDanger(scan, !weAreRed, !weAreOnMove) - corvetteDanger(scan, weAreRed, weAreOnMove)
      val hunt     = huntPressure(scan, weAreRed, phaseMultiplier) - huntPressure(scan, !weAreRed, phaseMultiplier)
      val space = spaceScore(scan, weAreRed, centerRow, centerCol, maxDistance) -
        spaceScore(scan, !weAreRed, centerRow, centerCol, maxDistance)

      val total = material + hanging + corvette + hunt + space

      /* The game is a draw after 30 turns with nobody exiled, and a draw is worth half a win. A side that is ahead
       * and drifting toward that counter is throwing away most of its advantage, so shade the advantage back toward
       * zero as the counter runs down - which makes forcing an exchange, and resetting it, worth something concrete.
       * Only positive scores are shaded: being behind, the same drift is a resource, not a problem. */
      if total > 0.0 then total * (1.0 - weights.drawFear * gameState.turnsSinceLastPieceDied / 30.0) else total

  end Scorer

  def evaluate(weights: TacticalWeights): (GameState, Team) => Double =
    val scorer = Scorer(weights)
    (gameState, team) => scorer.score(gameState, team)

  private val defaultScorer = new Scorer(TacticalWeights.default)

  /** The default weights - what [[be.doeraene.mad.ai.Player.tacticalPlayer]] plays with. Goes through a shared
    * [[Scorer]] rather than building one per call: this is invoked at every node of every search.
    */
  def evaluate(gameState: GameState, team: Team): Double = defaultScorer.score(gameState, team)

end TacticalEvaluator
