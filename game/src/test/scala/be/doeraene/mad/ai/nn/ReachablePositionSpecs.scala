package be.doeraene.mad.ai.nn

import scala.util.Random

import be.doeraene.mad.game.*
import be.doeraene.mad.ai.nn.NnTestSupport.*

/** The same laws as [[CanonicalChecks]], but on positions a game can actually reach.
  *
  * `GameActionChecks.gameStateGen` builds states by scattering an arbitrary subset of the pieces over arbitrary
  * squares, which is the right thing for covering odd corners of the rules but says nothing about whether the laws hold
  * along a real game - where the pieces thin out, the draw clock climbs and one side eventually wins.
  */
final class ReachablePositionSpecs extends munit.FunSuite:

  private val playouts = 50

  private def playRandomGame(boundaries: GameBoundaries, random: Random)(check: GameState => Unit): Int = {
    var state = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = true)
    var plies = 0
    while !state.ended && plies < 200 do
      check(state)
      val actions = state.allValidActions
      if actions.isEmpty then plies = 200
      else
        state = actions(random.nextInt(actions.length))(state)
        plies += 1
    check(state)
    plies
  }

  test("theLawsHoldAlongRealGames") {
    val random     = new Random(20260919)
    var positions  = 0
    var mirrorable = 0

    GameBoundaries.gameBoundaryByGameType.foreach { (gameType, boundaries) =>
      (0 until playouts).foreach { _ =>
        playRandomGame(boundaries, random) { state =>
          positions += 1
          assertEquals(Canonical.canonical(state).turnOfTeam, Team.Red, s"$gameType: $positions")

          if Canonical.mirrorIsExact(state) then
            mirrorable += 1
            val mirrored = state.mirrored

            assertEquals(
              mirrored.allValidActions.toVector.map(ActionIndex.indexOf).sorted,
              state.allValidActions.toVector.map(action => ActionIndex.indexOf(ActionIndex.mirror(action))).sorted,
              s"$gameType: the mirrored position offers different moves"
            )

            assertEquals(
              StateEncoder.encode(state).toVector,
              StateEncoder.encode(mirrored).toVector,
              s"$gameType: a position and its mirror encode differently"
            )

            state.allValidActions.toVector.foreach { action =>
              assert(
                samePositionIgnoringTurnNumber(
                  action(state).mirrored,
                  ActionIndex.mirror(action)(mirrored)
                ),
                s"$gameType: playing does not commute with mirroring for ${ActionIndex.descriptor(action)}"
              )
            }
        }
      }
    }

    // Guards against the whole test silently passing because the playouts died immediately.
    assert(positions > 2000, s"only $positions positions visited")
    assert(mirrorable > positions * 9 / 10, s"only $mirrorable of $positions positions were exactly mirrorable")
  }

  test("theInitialPositionEncodesAsExpected") {
    val boundaries = GameBoundaries.originalSixByFour
    val state      = GameState.initialGameStateWithBoundaries(boundaries, withInitialSpecialRule = false)
    val features   = StateEncoder.encode(state)
    val planeSize  = boundaries.lastRow * boundaries.lastCol

    assertEquals(features.length, StateEncoder.planeCount * planeSize)
    assertEquals(planeSize, 24)

    def planeSum(plane: Int): Float = features.slice(plane * planeSize, (plane + 1) * planeSize).sum
    def halfSum(offset: Int): Float = (0 until GamePiece.piecesPerTeam).map(slot => planeSum(offset + slot)).sum

    // Red moves first, so red fills the mover half; 8 pieces on each side, one square each.
    assertEquals(halfSum(StateEncoder.moverPlanes), 8f)
    assertEquals(halfSum(StateEncoder.opponentPlanes), 8f)
    assertEquals(planeSum(StateEncoder.boardMaskPlane), 24f)
    assertEquals(planeSum(StateEncoder.drawClockPlane), 0f)
    assertEquals(planeSum(StateEncoder.openingRulePlane), 0f)
  }

end ReachablePositionSpecs
