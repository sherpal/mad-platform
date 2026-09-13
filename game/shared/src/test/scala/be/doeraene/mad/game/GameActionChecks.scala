package be.doeraene.mad.game

import org.scalacheck._
import org.scalacheck.Prop._
import GameAction._
import Positions._

import scala.reflect.ClassTag

object GameActionChecks extends Properties("Game Action checks"):

  val permutations = Gen.oneOf(allPermutations)
  val rotations    = Gen.oneOf(allRotations)
  val movements1   = Gen.oneOf(oneMovements)
  val movements2   = Gen.oneOf(twoMovements)

  def randomPiecesPosition(
      boundaries: GameBoundaries
  ): Gen[Map[GamePiece, boundaries.Position]] = for {
    pieces    <- Gen.atLeastOne(GamePiece.pieces)
    positions <- Gen.atLeastOne(boundaries.allPositions)
  } yield pieces.zip(positions).toMap

  def gameStateGen(
      boundaries: GameBoundaries
  ): Gen[GameState] = for {
    turnNumber             <- Gen.choose(0, 100)
    turnsSinceLastDied     <- Gen.choose(0, 30)
    map                    <- randomPiecesPosition(boundaries)
    withInitialSpecialRule <- Gen.oneOf(true, false)
  } yield GameState(boundaries)(map, turnNumber, turnsSinceLastDied, withInitialSpecialRule)

  val sixByFourGameStateGen  = gameStateGen(GameBoundaries.originalSixByFour)
  val fiveByFiveGameStateGen = gameStateGen(GameBoundaries.defaultFiveByFive)

  val anyGameState: Gen[GameState] = Gen.oneOf(sixByFourGameStateGen, fiveByFiveGameStateGen)

  val gameStateWithValidAction: Gen[(GameState, GameAction)] = for {
    gameState <- anyGameState
    validActions = gameState.allValidActions
    if validActions.nonEmpty
    action <- Gen.oneOf(validActions)
  } yield (gameState, action)

  def gameStateWithActionType[ActionType <: GameAction](using
      classTag: ClassTag[ActionType]
  ): Gen[(GameState, ActionType)] = for {
    gameState <- anyGameState
    validActions = gameState.allValidActions.collect { case action: ActionType => action }
    if validActions.nonEmpty
    action <- Gen.oneOf(validActions)
  } yield (gameState, action)

  property("An action increases the turn number by one, and turns since last died if someone died") =
    forAll(gameStateWithValidAction) { (gameState: GameState, action: GameAction) =>
      val nextGameState = action(gameState)

      Prop(gameState.turnNumber + 1 == nextGameState.turnNumber) &&
      Prop(
        nextGameState.turnsSinceLastPieceDied ==
          (if action.doesSomeoneDie(gameState) then 0 else gameState.turnsSinceLastPieceDied + 1)
      )
    }

  property("A game state where the last row bonus action is legal exists") =
    exists(gameStateWithActionType[LastRowBonus])(_ => true)

  property("A Permutation does not change the entity count") = forAll(gameStateWithActionType[GameAction.Permutation]) {
    (gameState: GameState, permutation: GameAction.Permutation) =>
      gameState.pieceCount == permutation(gameState).pieceCount
  }

  property("A rotation does not change the entity count") = forAll(gameStateWithActionType[GameAction.Rotation]) {
    (gameState: GameState, rotation: GameAction.Rotation) =>
      gameState.pieceCount == rotation(gameState).pieceCount
  }

  property("A movement does make the entity count decrease if and only if the action says that someone dies") =
    forAll(gameStateWithActionType[GameAction.MovementAction]) {
      (gameState: GameState, movement: GameAction.MovementAction) =>
        val nextGameState = movement(gameState)

        def labelForFailure: String =
          s"""
           |Origin game state (${gameState.pieceCount} alive):
           |${gameState.prettyPrint}
           |Action is: ${movement}, piece is at position ${gameState.pieces.get(movement.piece)}
           |Resulting game state (${nextGameState.pieceCount} alive):
           |${nextGameState.prettyPrint}
           |Does someone die? ${movement.doesSomeoneDie(gameState)}
           |""".stripMargin

        Prop(
          gameState.pieceCount == nextGameState.pieceCount + (if movement.doesSomeoneDie(gameState) then 1 else 0)
        ) :| labelForFailure
    }

  property("No last row bonus action can take 111") = forAll(gameStateWithActionType[GameAction.LastRowBonus]) {
    (gameState, action) =>
      !action.movement1.maybeTakenPiece(gameState).contains(action.actionForTeam.otherTeam._111)
  }

  property("Valid last row bonus becomes not valid if 111 is at target position") = forAll(
    gameStateWithActionType[GameAction.LastRowBonus]
  ) { (gameStateU: GameState, action: GameAction.LastRowBonus) =>

    def testGameState(gameState: GameState): Prop = {
      val targetPosition: gameState.Position =
        action.movement1.finalPosition(gameState).get // valid action so get is safe
      val ennemy111 = action.actionForTeam.otherTeam._111
      val move111 = gameState.copy(
        pieces = gameState.pieces - gameState.piecesFromPosition
          .getOrElse(targetPosition, ennemy111) + (ennemy111 -> targetPosition)
      )

      (Prop(action.isLegal(gameState)) && Prop(!action.isLegal(move111))) :| s"""
        |Validity before: ${action.isLegal(gameState)}
        |Validity after: ${action.isLegal(move111)}
        |
        |Action: ${action.prettyPrint(gameState)}
        |
        |${move111.prettyPrint}
        |""".stripMargin
    }

    testGameState(gameStateU)

  }
