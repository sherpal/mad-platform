package be.doeraene.mad.game

import be.doeraene.mad.game.errors.OwnLegalityException
import be.doeraene.mad.game.errors.OwnLegalityException.*
import Positions.{Column, Direction, Position, Row}
import be.doeraene.mad.game.GameAction.{GamePieceMoves1, GamePieceMoves2}
import be.doeraene.perf.NatArray

import scala.util.Random

/** A [[GameAction]] acts on a [[GameState]] to produce a new one. */
sealed trait GameAction:

  def prettyPrint(gameState: GameState): String

  /** @see
    *   act
    */
  inline final def apply(gameState: GameState): GameState = act(gameState)

  /** Describes how the action act on the game state.
    *
    * An action is always assumed to be legal when performing the action.
    * @param gameState
    *   current [[GameState]]
    * @return
    *   [[GameState]] resulting of performing this action.
    */
  def act(gameState: GameState): GameState

  /** Returns whether this action can be taken, given that [[GameState]]. */
  def isLegal(
      gameState: GameState
  ): Boolean

  /** Returns [[scala.Left]] the [[OwnLegalityException]] when this action is not legal on its own (regardless of any
    * [[GameState]])
    */
  def ownLegality: Either[OwnLegalityException, this.type]

  /** Returns for which [[Team]] this action is. */
  def actionForTeam: Team

  /** Describes whether this action will result in a [[GamePiece]] dying. */
  def doesSomeoneDie(gameState: GameState): Boolean

  /** Returns the next state of the game with this new board. This function automatically increases the turn number, and
    * adjust the number of turns since last game piece dying.
    */
  final def newGameState(
      gameState: GameState
  )(
      newMap: Map[GamePiece, gameState.Position]
  ): GameState =
    GameState(gameState.gameBoundaries)(
      newMap,
      gameState.turnNumber + 1,
      if doesSomeoneDie(gameState) then 0 else gameState.turnsSinceLastPieceDied + 1,
      gameState.withInitialSpecialRule
    )

  /** Returns whether the [[GamePiece]] will move with this action. */
  def willPieceMove(gamePiece: GamePiece): Boolean

end GameAction

object GameAction:

  case class Identity(actionForTeam: Team) extends GameAction:
    def act(
        gameState: GameState
    ): GameState =
      newGameState(gameState)(gameState.pieces)
    def prettyPrint(
        gameState: GameState
    ): String = "Pass"
    def doesSomeoneDie(
        gameState: GameState
    ): Boolean = false
    def isLegal(
        gameState: GameState
    ): Boolean =
      gameState.withInitialSpecialRule && !gameState.teamAlreadyPlayed(actionForTeam)
    def ownLegality: Either[OwnLegalityException, this.type] = Right(this)
    def willPieceMove(gamePiece: GamePiece): Boolean         = false
  end Identity

  private val identityActions = NatArray(Identity(Team.Red), Identity(Team.Blue))

  sealed trait MovementAction extends GameAction:
    def piece: GamePiece
    def finalPosition(
        gameState: GameState
    ): Option[gameState.Position] // todo: I think we could do better

    def delta: Positions.Movement

    final def finalPositionPrint(
        gameState: GameState
    ): String = finalPosition(gameState).fold("???")(_.prettyPrint)

    final def takenPiecePrint(
        gameState: GameState
    ): String = maybeTakenPiece(gameState).fold("")(" and exile " ++ _.prettyPrint)

    final def prettyPrint(
        gameState: GameState
    ): String =
      val posPrint   = finalPositionPrint(gameState)
      val takenPrint = takenPiecePrint(gameState)
      s"Move ${piece.prettyPrint} to $posPrint$takenPrint"

    final def act(
        gameState: GameState
    ): GameState = {
      val targetPosition = finalPosition(gameState).get // this is supposed to be safe
      val perhapsRemovePreviousPiece: Map[GamePiece, gameState.Position] => Map[GamePiece, gameState.Position] =
        gameState.piecesFromPosition.get(targetPosition) match {
          case Some(piece) => _ - piece
          case None        => identity
        }
      newGameState(gameState)(perhapsRemovePreviousPiece(gameState.pieces) + (piece -> targetPosition))
    }

    def ownLegality: Either[OwnLegalityException, this.type] =
      OwnLegalityException.illegalPiece(piece).toLeft(this)

    final def actionForTeam: Team = piece.team

    final def doesSomeoneDie(
        gameState: GameState
    ): Boolean =
      finalPosition(gameState).exists(gameState.piecesFromPosition.isDefinedAt)
    final def maybeTakenPiece(
        gameState: GameState
    ): Option[GamePiece] =
      for {
        position   <- finalPosition(gameState)
        pieceThere <- gameState.piecesFromPosition.get(position)
      } yield pieceThere

    final def teamCanMove(
        gameState: GameState
    ): Boolean =
      gameState.teamAlreadyPlayed(actionForTeam) || !gameState.withInitialSpecialRule

    final def willPieceMove(gamePiece: GamePiece): Boolean = gamePiece == piece

  end MovementAction

  case class GamePieceMoves1(piece: GamePiece, direction: Direction) extends MovementAction:
    def finalPosition(
        gameState: GameState
    ): Option[gameState.Position] =
      for {
        currentPosition <- gameState.pieces.get(piece)
        afterMovement   <- currentPosition + direction
      } yield afterMovement

    def delta: Positions.Movement = direction.asMovement

    def isLegal(
        gameState: GameState
    ): Boolean =
      gameState.turnOfTeam == actionForTeam &&
        teamCanMove(gameState) &&
        finalPosition(gameState).exists(gameState.piecesFromPosition.get(_).fold(true)(piece.canTake))
  end GamePieceMoves1

  val oneMovements: NatArray[GamePieceMoves1] = for {
    direction <- Positions.directions
    piece     <- NatArray.from(GamePiece.pieces)
  } yield GamePieceMoves1(piece, direction)

  case class GamePieceMoves2(
      piece: GamePiece,
      firstPath: (Direction, Direction),
      alternativePaths: NatArray[(Direction, Direction)]
  ) extends MovementAction:
    val firstDirection: Direction  = firstPath._1
    val secondDirection: Direction = firstPath._2

    def delta: Positions.Movement = firstDirection.andThen(secondDirection)

    private val allPaths: NatArray[(Direction, Direction)] = alternativePaths.prepended(firstPath)
    private val allFirstDirections: NatArray[Direction]    = allPaths.map(_._1)

    def finalPosition(
        gameState: GameState
    ): Option[gameState.Position] = allPaths
      .map((firstDirection, secondDirection) =>
        for {
          currentPosition     <- gameState.pieces.get(piece)
          afterFirstMovement  <- currentPosition + firstDirection
          afterSecondMovement <- afterFirstMovement + secondDirection
        } yield afterSecondMovement
      )
      .collectFirst { case Some(position) => position }

    override def ownLegality: Either[OwnLegalityException, GamePieceMoves2.this.type] =
      List(
        super.ownLegality.swap.toOption,
        neutralPath(firstDirection, secondDirection),
        notAllPathsLeadToRome(allPaths),
        statMovementTooSmall(piece, 2)
      ).collectFirst { case Some(exception) =>
        exception
      }.toLeft(this)

    def existsEmptyFirstPosition(
        gameState: GameState
    ): Boolean =
      gameState.pieces
        .get(piece)
        .exists(initialPosition =>
          allFirstDirections.exists(dir =>
            (initialPosition + dir).exists(middlePosition => !gameState.piecesFromPosition.isDefinedAt(middlePosition))
          )
        )

    def isLegal(
        gameState: GameState
    ): Boolean =
      gameState.turnOfTeam == actionForTeam &&
        teamCanMove(gameState) &&
        existsEmptyFirstPosition(gameState) &&
        (finalPosition(gameState) match {
          case None           => false
          case Some(position) => gameState.piecesFromPosition.get(position).fold(true)(piece.canTake)
        })
  end GamePieceMoves2

  val twoMovements: NatArray[GamePieceMoves2] = for {
    targetAndPaths <- NatArray.from(Positions.all2LengthPaths.groupBy(_ + _))
    (_, paths)       = targetAndPaths
    firstPath        = paths.head
    alternativePaths = paths.tail
    piece <- NatArray.from(GamePiece.pieces)
    if piece.movement >= 2
  } yield GamePieceMoves2(piece, firstPath, alternativePaths)

  private val allMovements: NatArray[MovementAction] = oneMovements ++ twoMovements

  /** [[allMovements]] grouped by piece, computed once. Several hot-path lookups (piece-specific mobility/threat counts,
    * called from [[GamePiece.pieceTakeScore]], [[GamePiece.piecesTakenScore]] and eval heuristics) only ever care about
    * one piece's own moves; scanning and filtering the full ~128-entry [[allMovements]] list for that on every call, at
    * every node of a search tree, is pure waste when this index turns it into an O(1) lookup into a list of only that
    * piece's own handful of moves.
    */
  val movementsByPiece: Map[GamePiece, NatArray[MovementAction]] =
    allMovements.toVector.groupBy(_.piece).map((piece, actions) => piece -> NatArray.from(actions))

  sealed trait PieceShiftingAction extends GameAction:
    def involvedPieces: Vector[GamePiece]

    final def willPieceMove(gamePiece: GamePiece): Boolean = involvedPieces.contains(gamePiece)

  case class Permutation(piece1: GamePiece, piece2: GamePiece) extends PieceShiftingAction:
    def prettyPrint(
        gameState: GameState
    ): String =
      s"Permutation: ${piece1.prettyPrint} <-> ${piece2.prettyPrint}"

    val involvedPieces: Vector[GamePiece] = Vector(piece1, piece2)

    def act(
        gameState: GameState
    ): GameState = {
      val maybePositionOfPiece1 = gameState.pieces.get(piece1)
      val maybePositionOfPiece2 = gameState.pieces.get(piece2)
      def placePiece1(map: Map[GamePiece, gameState.Position]): Map[GamePiece, gameState.Position] =
        maybePositionOfPiece2 match {
          case None           => map - piece1
          case Some(position) => map + (piece1 -> position)
        }
      def placePiece2(map: Map[GamePiece, gameState.Position]): Map[GamePiece, gameState.Position] =
        maybePositionOfPiece1 match {
          case None           => map - piece2
          case Some(position) => map + (piece2 -> position)
        }
      newGameState(gameState)(placePiece1(placePiece2(gameState.pieces)))
    }

    def isLegal(gameState: GameState): Boolean = gameState.pieceIsAlive(piece1) || gameState.pieceIsAlive(piece2)

    def ownLegality: Either[OwnLegalityException, this.type] = List(
      illegalPiece(piece1),
      illegalPiece(piece2),
      notOnSameTeam(piece1, piece2),
      notOpposites(piece1, piece2)
    ).collectFirst { case Some(exception) =>
      exception
    }.toLeft(this)

    def actionForTeam: Team = piece1.team
    def doesSomeoneDie(
        gameState: GameState
    ): Boolean = false
  end Permutation

  val allPermutations: NatArray[Permutation] = NatArray.from(for {
    piece1 <- GamePiece.pieces
    if piece1.attack == GamePiece.attack1 // only doing for attack = 1 pieces, otherwise we have twice the same actions.
    piece2 <- GamePiece.oppositePieces.get(piece1)
  } yield Permutation(piece1, piece2))

  case class Rotation(piece1: GamePiece, piece2: GamePiece, piece3: GamePiece) extends PieceShiftingAction:
    def prettyPrint(
        gameState: GameState
    ): String =
      s"Rotation: ${piece1.prettyPrint} -> ${piece2.prettyPrint} -> ${piece3.prettyPrint}"

    val involvedPieces: Vector[GamePiece] = Vector(piece1, piece2, piece3)

    def act(
        gameState: GameState
    ): GameState = {
      val maybePositionOfPiece1 = gameState.pieces.get(piece1)
      val maybePositionOfPiece2 = gameState.pieces.get(piece2)
      val maybePositionOfPiece3 = gameState.pieces.get(piece3)
      def placePiece1(map: Map[GamePiece, gameState.Position]): Map[GamePiece, gameState.Position] =
        maybePositionOfPiece2 match {
          case None           => map - piece1
          case Some(position) => map + (piece1 -> position)
        }
      def placePiece2(map: Map[GamePiece, gameState.Position]): Map[GamePiece, gameState.Position] =
        maybePositionOfPiece3 match {
          case None           => map - piece2
          case Some(position) => map + (piece2 -> position)
        }
      def placePiece3(map: Map[GamePiece, gameState.Position]): Map[GamePiece, gameState.Position] =
        maybePositionOfPiece1 match {
          case None           => map - piece3
          case Some(position) => map + (piece3 -> position)
        }
      val rotation = placePiece1 andThen placePiece2 andThen placePiece3
      newGameState(gameState)(rotation(gameState.pieces))
    }

    def isLegal(
        gameState: GameState
    ): Boolean =
      involvedPieces.count(gameState.pieceIsAlive) >= 2

    def ownLegality: Either[OwnLegalityException, this.type] = List(
      illegalPiece(piece1),
      illegalPiece(piece2),
      illegalPiece(piece3),
      notRotationPool(Set(piece1, piece2, piece3))
    ).collectFirst { case Some(exception) =>
      exception
    }.toLeft(this)

    def actionForTeam: Team = piece1.team
    def doesSomeoneDie(
        gameState: GameState
    ): Boolean = false
  end Rotation

  val allRotations: NatArray[Rotation] = GamePiece.rotationPools.map(NatArray.from).flatMap { arr =>
    if arr.length != 3 then throw RuntimeException("yewh")
    val piece1 = arr(0)
    val piece2 = arr(1)
    val piece3 = arr(2)
    NatArray(Rotation(piece1, piece2, piece3), Rotation(piece1, piece3, piece2))
  }

  case class LastRowBonus(movement1: GamePieceMoves1, shiftAction: PieceShiftingAction) extends GameAction:
    /* Own legality already checks that both actions are for the same team. */
    def actionForTeam: Team = movement1.actionForTeam

    def act(
        gameState: GameState
    ): GameState =
      val otherGameState: GameState = shiftAction(movement1(gameState))
      val pieces: Map[GamePiece, gameState.gameBoundaries.Position] = otherGameState.pieces
        .map((gamePiece, position) =>
          gamePiece -> gameState.gameBoundaries.positionFromOtherBoundaries(otherGameState.gameBoundaries)(position)
        )
      newGameState(gameState)(pieces)

    def prettyPrint(
        gameState: GameState
    ): String =
      "Bonus: " ++ movement1.prettyPrint(gameState) ++ " then " ++ shiftAction.prettyPrint(movement1(gameState))

    def doesSomeoneDie(
        gameState: GameState
    ): Boolean =
      movement1.doesSomeoneDie(gameState)

    def isLegal(
        gameState: GameState
    ): Boolean =
      import gameState.gameBoundaries.given
      movement1.isLegal(gameState) && movement1
        .finalPosition(gameState)
        .exists(gameState.gameBoundaries.bonusActionPositions(actionForTeam) contains _) &&
      shiftAction
        .isLegal(movement1(gameState)) && !movement1.maybeTakenPiece(gameState).contains(actionForTeam.otherTeam._111)

    def ownLegality: Either[OwnLegalityException, this.type] =
      for {
        _ <- movement1.ownLegality
        _ <- shiftAction.ownLegality
        _ <- OwnLegalityException.compositionActionsNotAllInSameTeam(movement1, shiftAction).toLeft(())
        _ <- OwnLegalityException.shiftingActionDoesNotInvolvePiece(movement1.piece, shiftAction).toLeft(())
      } yield this

    def willPieceMove(gamePiece: GamePiece): Boolean =
      movement1.willPieceMove(gamePiece) || shiftAction.willPieceMove(gamePiece)
  end LastRowBonus

  private val lastRowBonusActions: NatArray[LastRowBonus] = for {
    movement    <- oneMovements
    shiftAction <- allPermutations ++ allRotations
    if movement.actionForTeam == shiftAction.actionForTeam
    if shiftAction.involvedPieces.contains(movement.piece)
  } yield LastRowBonus(movement, shiftAction)

  val allActions: NatArray[GameAction] =
    identityActions.map(a => a: GameAction) ++ allMovements ++ allPermutations ++ allRotations ++ lastRowBonusActions
  val blueActions: NatArray[GameAction] = allActions.filter(_.actionForTeam == Team.Blue)
  val redActions: NatArray[GameAction]  = allActions.filter(_.actionForTeam == Team.Red)

  def randomAction(
      gameState: GameState
  ): GameAction =
    val actions = gameState.allValidActions
    actions(Random.nextInt(actions.length))

  /** Given the initial [[GameState]] and a list of [[GameAction]] returns a list of pairs containing all the
    * [[GameState]] induced by these actions, together with the action taken at that [[GameState]].
    *
    * Note: this misses the "current" [[GameState]], that you can obtain via
    * {{{
    *   .lastOption.map((gs, a) => a(gs)).getOrElse(initialGameState)
    * }}}
    */
  def reconstructGameStates(
      allActions: List[GameAction],
      initialGameState: GameState
  ): List[(GameState, GameAction)] = {
    @scala.annotation.tailrec
    def accumulator(
        currentGameState: GameState,
        remainingActions: List[GameAction],
        acc: List[(GameState, GameAction)]
    ): List[(GameState, GameAction)] = remainingActions match {
      case Nil => acc.reverse
      case nextAction :: remainingActionsAfter =>
        accumulator(nextAction(currentGameState), remainingActionsAfter, (currentGameState, nextAction) +: acc)
    }

    accumulator(initialGameState, allActions, Nil)
  }

end GameAction
