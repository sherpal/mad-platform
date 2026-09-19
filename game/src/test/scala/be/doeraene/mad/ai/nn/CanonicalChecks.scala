package be.doeraene.mad.ai.nn

import org.scalacheck.*
import org.scalacheck.Prop.*

import be.doeraene.mad.game.*
import be.doeraene.mad.ai.nn.NnTestSupport.*

/** Checks that the vertical mirror really is a symmetry of the game, and not just of the starting position.
  *
  * The whole canonicalisation rests on this. If mirroring did not commute with playing, a network trained on
  * canonicalised positions would be learning two different games at once and there would be nothing to point at: no
  * crash, no type error, just an engine that quietly never gets good.
  */
object CanonicalChecks extends Properties("Canonical checks"):

  private val anyBoundaries: Gen[GameBoundaries] =
    Gen.oneOf(GameBoundaries.gameBoundaryByGameType.values.toList)

  private val anyGameState: Gen[GameState] = anyBoundaries.flatMap(GameActionChecks.gameStateGen)

  /** States whose mirror is faithful, which is everything but the second ply under the opening rule. */
  private val exactlyMirrorableGameState: Gen[GameState] = anyGameState.filter(Canonical.mirrorIsExact)

  private val gameStateWithLegalAction: Gen[(GameState, GameAction)] = for {
    gameState <- exactlyMirrorableGameState
    legalActions = gameState.allValidActions
    if legalActions.nonEmpty
    action <- Gen.oneOf(legalActions.toVector)
  } yield (gameState, action)

  property("canonicalPutsRedToMove") = forAll(anyGameState) { gameState =>
    Canonical.canonical(gameState).turnOfTeam == Team.Red
  }

  property("canonicalIsIdentityWhenRedIsAlreadyToMove") = forAll(anyGameState) { gameState =>
    (gameState.turnOfTeam == Team.Red) ==> (Canonical.canonical(gameState) eq gameState)
  }

  property("mirroringPreservesThePosition") = forAll(anyGameState) { gameState =>
    val mirrored = gameState.mirrored
    all(
      "flips the turn" |: mirrored.turnOfTeam == gameState.turnOfTeam.otherTeam,
      "keeps every piece" |: mirrored.pieceCount == gameState.pieceCount,
      "keeps the draw clock" |: mirrored.turnsSinceLastPieceDied == gameState.turnsSinceLastPieceDied,
      "swaps the winner" |: mirrored.maybeWinner == gameState.maybeWinner.map(_.otherTeam),
      "ends exactly when the original does" |: mirrored.ended == gameState.ended,
      "lands every piece on a real square" |: mirrored.pieces.forall((_, position) =>
        mirrored.gameBoundaries.allPositions.contains(position)
      )
    )
  }

  property("mirroringTwiceRestoresThePosition") = forAll(anyGameState) { gameState =>
    samePositionIgnoringTurnNumber(gameState.mirrored.mirrored, gameState)
  }

  /** The property the whole scheme hangs on. Checked over every action rather than only the legal ones, so that it also
    * pins down which moves the mirrored position offers.
    */
  property("legalityMirrors") = forAll(exactlyMirrorableGameState) { gameState =>
    val mirrored = gameState.mirrored
    GameAction.allActions.toVector.forall { action =>
      action.isLegal(gameState) == ActionIndex.mirror(action).isLegal(mirrored)
    }
  }

  property("playingCommutesWithMirroring") = forAll(gameStateWithLegalAction) { (gameState, action) =>
    samePositionIgnoringTurnNumber(
      action(gameState).mirrored,
      ActionIndex.mirror(action)(gameState.mirrored)
    )
  }

  property("policyIndexRoundTrips") = forAll(gameStateWithLegalAction) { (gameState, action) =>
    val index = Canonical.policyIndex(gameState, action)
    (index >= 0 && index < ActionIndex.teamSize) &&
    Canonical.actionFromPolicyIndex(gameState, index) == action
  }

  property("legalPolicyMaskCountsTheLegalActions") = forAll(exactlyMirrorableGameState) { gameState =>
    // Also proves policyIndex is injective on the legal actions: if two of them collided the mask would come up short.
    Canonical.legalPolicyMask(gameState).count(_ == 1f) == gameState.allValidActions.length
  }

end CanonicalChecks
