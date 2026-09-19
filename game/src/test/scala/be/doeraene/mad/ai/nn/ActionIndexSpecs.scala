package be.doeraene.mad.ai.nn

import be.doeraene.mad.game.*

final class ActionIndexSpecs extends munit.FunSuite:

  private val everyAction: Vector[GameAction] = GameAction.allActions.toVector

  test("indexRoundTrips") {
    everyAction.zipWithIndex.foreach { (action, index) =>
      assertEquals(ActionIndex.indexOf(action), index, ActionIndex.descriptor(action))
      assertEquals(ActionIndex.fromIndex(index), action, ActionIndex.descriptor(action))
    }
  }

  test("keysAreInjective") {
    // indexOf goes through a Map keyed on a packed Long. If two actions ever collided, one of them would silently be
    // indexed as the other rather than failing, so this is the test that the packing has enough room.
    assertEquals(everyAction.map(ActionIndex.indexOf).distinct.length, ActionIndex.size)
    assertEquals(everyAction.map(ActionIndex.descriptor).distinct.length, ActionIndex.size)
  }

  test("indexOfWorksOnRebuiltActions") {
    // The reason ActionIndex exists at all: GamePieceMoves2 holds an Array, so a structurally rebuilt copy is not
    // `==` to the canonical one and `allActions.indexOf` would return -1 for it.
    val canonical = GameAction.twoMovements(0)
    val rebuilt   = GameAction.GamePieceMoves2(canonical.piece, canonical.firstPath, canonical.alternativePaths.clone())

    assertNotEquals(rebuilt, canonical, "expected the Array field to defeat case class equality")
    assertEquals(ActionIndex.indexOf(rebuilt), ActionIndex.indexOf(canonical))
    assertEquals(ActionIndex.fromIndex(ActionIndex.indexOf(rebuilt)), canonical: GameAction)
  }

  test("mirrorIsAnInvolution") {
    everyAction.foreach { action =>
      assertEquals(ActionIndex.mirror(ActionIndex.mirror(action)), action, ActionIndex.descriptor(action))
    }
  }

  test("mirrorSwapsTeams") {
    everyAction.foreach { action =>
      assertEquals(
        ActionIndex.mirror(action).actionForTeam,
        action.actionForTeam.otherTeam,
        ActionIndex.descriptor(action)
      )
    }
  }

  test("teamIndicesAreABijectionPerTeam") {
    val (red, blue) = everyAction.zipWithIndex.partition(_._1.actionForTeam == Team.Red)

    assertEquals(red.map((_, index) => ActionIndex.teamIndices(index)).sorted, (0 until ActionIndex.teamSize).toVector)
    assertEquals(blue.map((_, index) => ActionIndex.teamIndices(index)).sorted, (0 until ActionIndex.teamSize).toVector)
  }

  test("redIndicesInvertsTeamIndices") {
    (0 until ActionIndex.teamSize).foreach { teamIndex =>
      val fullIndex = ActionIndex.redIndices(teamIndex)
      assertEquals(ActionIndex.teamIndices(fullIndex), teamIndex)
      assertEquals(GameAction.allActions(fullIndex).actionForTeam, Team.Red)
    }
  }

end ActionIndexSpecs
