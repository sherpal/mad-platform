package be.doeraene.mad.ai

import be.doeraene.mad.game.{GameAction, GamePiece, GameState, Team}
import be.doeraene.perf.NatArray

/** A hand-crafted [[GameState]] evaluator, built from a structural read of the ship stats rather than from the
  * corvette/frigate/destroyer/cruiser naming order.
  *
  * The starting observation: a capture only ever checks `attacker.attack >= defender.defence`. `movement` never enters
  * that decision, it's pure mobility. So the "true" combat tier of a piece is set by its `(attack, defence)` pair, and
  * it does not line up with the naming: 211 (a "frigate") has `attack=1, defence=1`, exactly as combat-weak as 111
  * itself, while 122 (a "destroyer") has `attack=2, defence=2`, exactly as combat-strong as 222. `movement` only earns
  * a small secondary bonus.
  *
  * On top of that material scale:
  *   - a recall-aware correction: an exiled piece whose complementary piece is still on the board isn't fully dead, it
  *     can come back via a permutation (at the cost of a turn), except for 222, whose complement is 111 - "recalling"
  *     it would mean exiling your own 111, never a real option, so its exile is final, matching the rulebook ("once
  *     expelled, it can no longer be called back").
  *   - a heavily-weighted 111 safety term: 111 always has `defence=1`, so *every* opposing piece threatens it, and
  *     there is no "check" in this game - if it's exposed and undefended, it is simply lost next turn. This term is
  *     deliberately the dominant one.
  *   - a phase-scaled 222 caution term: the cruiser is the strongest piece and its loss is permanent, but that matters
  *     most early, while many enemies remain to gang up on it; the caution fades as pieces trade off, so it doesn't
  *     just sit passively in the back row all game.
  *   - a hunt-pressure term: pulls our non-111 pieces toward the opponent's 111, so the evaluator has an actual plan to
  *     win rather than just a plan to not lose.
  *   - a small centre-control term, weighted a bit more for movement-2 pieces, which benefit more from it.
  *
  * Every numeric constant behind these terms lives in [[ClaudeWeights]] rather than here, so a tuner (see
  * [[be.doeraene.mad.ai.tuning.ClaudeWeightTuner]]) can search over them without touching this logic.
  *
  * The known 111-vs-111 endgame (see [[be.doeraene.mad.ai.minimax.TreeExplorer.queenVSQueenSituation]]) is handled
  * upstream by [[be.doeraene.mad.ai.minimax.TreeExplorer.MadGameStateTreeExplorer]] regardless of which evaluator is
  * plugged in, so this evaluator never needs to worry about it.
  */
object ClaudeEvaluator:

  // --- material ---------------------------------------------------------------------------------------------

  /** Net combat score of an (attack, defence) pair: how many of the 8 opponent types this piece can take, minus how
    * many opponent types can take it. Only depends on (attack, defence): attack=2 can take all 8 types, attack=1 only
    * the 4 with defence=1; defence=2 is only taken by the 4 attack=2 types, defence=1 is taken by all 8.
    */
  private def combatNet(attack: Int, defence: Int): Double =
    val captureCount    = if attack == 2 then 8.0 else 4.0
    val vulnerableCount = if defence == 1 then 8.0 else 4.0
    captureCount - vulnerableCount

  /** Material value of a piece still on the board. 111 gets none: its importance is entirely the terminal condition and
    * the safety term below, never a tradeable material amount.
    */
  private def materialValue(weights: ClaudeWeights, piece: GamePiece): Double =
    if piece.is111 then 0.0
    else
      val (movement, attack, defence) = piece.statValues
      weights.materialBase + weights.materialAlpha * combatNet(attack, defence) + weights.materialBeta * (movement - 1)

  /** Material score for one team, crediting exiled pieces that can still be recalled by permutation. */
  private def materialScore(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    GamePiece.pieces.iterator
      .filter(_.team == team)
      .map { piece =>
        if gameState.pieceIsAlive(piece) then materialValue(weights, piece)
        else if piece.is222 then 0.0 // recalling 222 would require exiling our own 111: never a real option
        else
          val partner = GamePiece.oppositePieces(piece)
          if gameState.pieceIsAlive(partner) then weights.recallCredit * materialValue(weights, piece) else 0.0
      }
      .sum

  // --- 111 safety ---------------------------------------------------------------------------------------------

  /** Unlike [[GamePiece.piecesTakenScore]], which only fires once a capture is immediately available, this ramps up a
    * few squares out - a movement-2 piece starts contributing once within 4 squares, a movement-1 piece within 3 - so a
    * converging enemy shows up as a gradient the search can act on before it's a forced, too-late reaction. This
    * matters a lot at shallow depths: with only 3-4 plies of lookahead, a purely binary "am I in check right now" term
    * can leave the 111 boxed into a corner before any danger was ever visible to the search.
    */
  private def corvetteApproachPressure(gameState: GameState, team: Team): Double =
    gameState.maybePiecePosition(team._111) match
      case None => 0.0
      case Some(myPosition) =>
        GamePiece.pieces.iterator
          .filter(_.team == team.otherTeam)
          .flatMap(piece => gameState.maybePiecePosition(piece).map(piece -> _))
          .map { case (piece, position) =>
            val distance = position.distanceTo(myPosition)
            val reach    = if piece.movement >= 2 then 2 else 1
            math.max(0.0, (reach + 2) - distance)
          }
          .sum

  /** 111 has movement 1: it can never outrun a movement-2 attacker in open ground, only friendly pieces nearby can
    * block the approach or contest the square. Without this term, the rest of the evaluator is happy to trade away
    * every piece near the 111 as long as the 111 itself isn't in immediate danger yet - and then discovers, a couple of
    * moves later, that nothing is left close enough to help once a fast attacker does close in.
    */
  private def bodyguardSupport(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    gameState.maybePiecePosition(team._111) match
      case None => 0.0
      case Some(myPosition) =>
        GamePiece.pieces.iterator
          .filter(piece => piece.team == team && !piece.is111)
          .flatMap(piece => gameState.maybePiecePosition(piece).map(piece -> _))
          .map { case (_, position) => math.max(0.0, 4 - position.distanceTo(myPosition)) }
          .sum * weights.bodyguardWeight

  private def corvetteSafety(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    val own111 = team._111
    if !gameState.pieceIsAlive(own111) then 0.0 // would be terminal, handled upstream; defensive only
    else
      val threats = own111.piecesTakenScore(gameState)
      val escapes = GameAction.movementsByPiece
        .getOrElse(own111, NatArray.empty[GameAction.MovementAction])
        .count(_.isLegal(gameState))
        .toDouble
      val pressure  = corvetteApproachPressure(gameState, team)
      val bodyguard = bodyguardSupport(weights, gameState, team)
      -weights.corvetteThreatWeight * threats + weights.corvetteEscapeWeight * escapes -
        weights.corvetteApproachWeight * pressure + bodyguard

  // --- 222 caution ---------------------------------------------------------------------------------------------

  private def cruiserCaution(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    val own222 = GamePiece.oppositePieces(team._111)
    if !gameState.pieceIsAlive(own222) then 0.0 // already permanently lost, material term already reflects that
    else
      val phase    = gameState.pieceCount / 16.0 // 1.0 at the start, shrinks as pieces trade off
      val exposure = own222.piecesTakenScore(gameState)
      -weights.cruiserCautionWeight * phase * exposure

  // --- hunting the opponent's 111 --------------------------------------------------------------------------------

  /** Without a term actively pulling pieces toward the opponent's 111, an evaluator built only from safety and material
    * tends to play soundly but passively: good at not losing, with no plan for winning, letting games drift into thin
    * endgames on the opponent's terms. This rewards each of our non-111 pieces (111 itself stays out of it - it's too
    * fragile to go hunting) for being few turns away from the opponent's 111, using the same turns-to-travel
    * [[GamePiece.turnToGoToOpponent111]] jPaul's theory is built on, tracking its *current* position rather than a
    * snapshot - reasonable here since this is recomputed fresh at every node the search visits anyway.
    *
    * Phase-scaled the other way from the cruiser caution above: early, with many enemies still able to counter-attack,
    * pulling escorts away from our own 111 to go hunting is exactly how it ends up undefended a few moves later, so the
    * pull starts weak; it strengthens as pieces thin out and the game increasingly comes down to a direct race.
    */
  private def huntPressure(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    val phase           = gameState.pieceCount / 16.0 // 1.0 at the start, shrinks as pieces trade off
    val phaseMultiplier = 1.5 - phase                 // 0.5 with a full board, up to ~1.375 down to the two-111 ending

    GamePiece.pieces.iterator
      .filter(piece => piece.team == team && !piece.is111 && gameState.pieceIsAlive(piece))
      .map { piece =>
        val turnsToGo = piece.turnToGoToOpponent111(gameState)
        weights.huntWeight * math.max(0.0, weights.huntHorizon - turnsToGo)
      }
      .sum * phaseMultiplier

  // --- centre control -------------------------------------------------------------------------------------------

  private def centerControl(weights: ClaudeWeights, gameState: GameState, team: Team): Double =
    val (lastRow, lastCol) = gameState.shape
    val centerRow          = (lastRow - 1) / 2.0
    val centerCol          = (lastCol - 1) / 2.0
    val maxDistance        = centerRow + centerCol

    GamePiece.pieces.iterator
      .filter(_.team == team)
      .flatMap(piece => gameState.maybePiecePosition(piece).map(piece -> _))
      .map { case (piece, position) =>
        val (row, col)    = position.asDoublePair
        val proximity     = maxDistance - (math.abs(row - centerRow) + math.abs(col - centerCol))
        val mobilityBoost = if piece.movement >= 2 then 1.5 else 1.0
        weights.centerWeight * proximity * mobilityBoost
      }
      .sum

  // --- putting it together ---------------------------------------------------------------------------------------

  /** Builds a [[Node.Evaluator]]-shaped function for a given set of weights - what a tuner scores candidates with. */
  def evaluate(weights: ClaudeWeights): (GameState, Team) => Double =
    (gameState, team) =>
      val opponent = team.otherTeam

      val material = materialScore(weights, gameState, team) - materialScore(weights, gameState, opponent)
      val corvette = corvetteSafety(weights, gameState, team) - corvetteSafety(weights, gameState, opponent)
      val cruiser  = cruiserCaution(weights, gameState, team) - cruiserCaution(weights, gameState, opponent)
      val hunt     = huntPressure(weights, gameState, team) - huntPressure(weights, gameState, opponent)
      val center   = centerControl(weights, gameState, team) - centerControl(weights, gameState, opponent)

      material + corvette + cruiser + hunt + center

  /** The hand-picked default weights - what [[be.doeraene.mad.ai.Player.claudeTheoryPlayer]] plays with. */
  def evaluate(gameState: GameState, team: Team): Double = evaluate(ClaudeWeights.default)(gameState, team)

end ClaudeEvaluator
