package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*

/** Reduces a position to the single point of view a network is trained on: red to move.
  *
  * Mad is symmetric under reflecting the board across its horizontal mid-line and swapping the two teams - all four
  * [[GameBoundaries]] have vertically symmetric shapes and point-reflected starting positions - so a position with blue
  * to move is the same problem as its mirror with red to move. Training on one point of view instead of two roughly
  * halves the data a network needs, and shrinks the policy head from [[ActionIndex.size]] to [[ActionIndex.teamSize]]
  * outputs.
  *
  * The catch is that everything crossing the boundary has to be mirrored consistently: the state on the way in, the
  * chosen action on the way out. [[policyIndex]] and [[actionFromPolicyIndex]] are the pair that does that, and are
  * what search code should call rather than mirroring by hand.
  *
  * @see
  *   [[GameState.mirrored]] for the symmetry itself, and for the one case where it is not exact.
  */
object Canonical:

  /** Whether this state has to be mirrored to put red to move. */
  def needsMirroring(gameState: GameState): Boolean = gameState.turnOfTeam == Team.Blue

  /** Whether mirroring and canonicalising preserve everything the rules look at, which is everywhere but the opening
    * plies of a game played with the opening rule.
    *
    * [[GameState.mirrored]] alone only misreports `turnNumber == 2`, but canonicalisation may mirror twice - mirroring
    * a state that already has red to move lands on `turnNumber + 2` - and [[GameState.teamAlreadyPlayed]] is unstable
    * under that shift for the first two plies as well. So the honest condition covers both.
    *
    * Worth asserting when generating training data: a position where this is false encodes as if its mover had already
    * moved, and so loses the legality of passing. It is two positions per game, so skipping them costs nothing.
    */
  def mirrorIsExact(gameState: GameState): Boolean =
    !gameState.withInitialSpecialRule || gameState.turnNumber > 2

  /** This state seen with red to move. Identity when red is already to move. */
  def canonical(gameState: GameState): GameState =
    if needsMirroring(gameState) then gameState.mirrored else gameState

  /** The index a network's policy head should put this action at, given the state it is played in.
    *
    * `action` is a legal action of `gameState`, so it belongs to the team to move; the result is in
    * `0 until ActionIndex.teamSize`.
    */
  def policyIndex(gameState: GameState, action: GameAction): Int =
    val canonicalAction = if needsMirroring(gameState) then ActionIndex.mirror(action) else action
    ActionIndex.teamIndices(ActionIndex.indexOf(canonicalAction))

  /** Inverse of [[policyIndex]]: the action `gameState`'s player has to play for the canonicalised network to have
    * meant output `index`.
    */
  def actionFromPolicyIndex(gameState: GameState, index: Int): GameAction =
    val canonicalAction = ActionIndex.fromIndex(ActionIndex.redIndices(index))
    if needsMirroring(gameState) then ActionIndex.mirror(canonicalAction) else canonicalAction

  /** Mask of which policy outputs are playable in this state, as `1f` / `0f` so it can be multiplied straight into a
    * head's output. Illegal moves have to be masked before the softmax, not after: a network is never trained to push
    * them down, so it will happily assign them mass.
    */
  def legalPolicyMask(gameState: GameState): Array[Float] =
    val mask    = new Array[Float](ActionIndex.teamSize)
    val actions = gameState.allValidActions
    var index   = 0
    while index < actions.length do
      mask(policyIndex(gameState, actions(index))) = 1f
      index += 1
    mask

end Canonical
