package be.doeraene.mad.game.errors

import be.doeraene.mad.game.{GamePiece, Positions, GameAction}
import be.doeraene.mad.game.Positions.Direction

/**
 * Represents that an action is illegal on its own.
 */
abstract class OwnLegalityException(msg: String) extends Exception(msg)

object OwnLegalityException:

  private class IllegalPiece(piece: GamePiece) extends OwnLegalityException(s"This piece ${piece.prettyPrint} is not valid.")
  private class NeutralPath(firstDirection: Direction, secondDirection: Direction) 
    extends OwnLegalityException(s"This two directions are opposite of each other: $firstDirection and $secondDirection")
  private class StatMovementTooSmall(piece: GamePiece, movement: Int)
    extends OwnLegalityException(s"This piece has ${piece.movement} movements but we ask it to do $movement.")
  private class NotOnSameTeam(piece1: GamePiece, piece2: GamePiece)
    extends OwnLegalityException(s"This two pieces are not in the same team: $piece1 and $piece2")
  private class NotOpposites(piece1: GamePiece, piece2: GamePiece)
    extends OwnLegalityException(s"This two pieces are not opposite of each other: $piece1 and $piece2")
  private class NotRotationPool(pieces: Set[GamePiece])
    extends OwnLegalityException(s"This pieces to not form a rotation pool: ${pieces.mkString(", ")}")
  private class CompositeActionAreNotInSameTeam(actions: List[GameAction])
    extends OwnLegalityException(s"These actions are not all for the same team: ${actions.mkString(", ")}")

  final class NotAllPathsLeadToRome(targetPositionOne: (Int, Int), targetPositionTwo: (Int, Int), paths: List[(Direction, Direction)])
    extends OwnLegalityException(s"All the paths for this action don't lead to the same destination (found at least $targetPositionOne and $targetPositionTwo and these paths: ${paths.mkString(", ")})")
  
  def illegalPiece(piece: GamePiece): Option[OwnLegalityException] = 
    Option.unless(GamePiece.pieces.contains(piece))(IllegalPiece(piece))
    
  def neutralPath(firstDirection: Direction, secondDirection: Direction): Option[OwnLegalityException] =
    Option.unless(Positions.all2LengthPaths.contains((firstDirection, secondDirection)))(
      NeutralPath(firstDirection, secondDirection)
    )
    
  def statMovementTooSmall(piece: GamePiece, movement: Int): Option[OwnLegalityException] =
    Option.unless(piece.movement >= movement)(StatMovementTooSmall(piece, movement))
    
  def notOnSameTeam(piece1: GamePiece, piece2: GamePiece): Option[OwnLegalityException] =
    Option.unless(piece1.team == piece2.team)(NotOnSameTeam(piece1, piece2))
    
  def notOpposites(piece1: GamePiece, piece2: GamePiece): Option[OwnLegalityException] =
    Option.unless(GamePiece.oppositePieces.get(piece1) == Some(piece2))(NotOpposites(piece1, piece2))
    
  def notRotationPool(pieces: Set[GamePiece]): Option[OwnLegalityException] =
    Option.unless(GamePiece.rotationPools contains pieces)(NotRotationPool(pieces))

  def compositionActionsNotAllInSameTeam(actions: List[GameAction]): Option[OwnLegalityException] =
    Option.unless(actions match {
      case Nil => true
      case head :: tail => tail.forall(_.actionForTeam == head.actionForTeam)
    })(CompositeActionAreNotInSameTeam(actions))
  def compositionActionsNotAllInSameTeam(actions: GameAction*): Option[OwnLegalityException] =
    compositionActionsNotAllInSameTeam(actions.toList)

  def shiftingActionDoesNotInvolvePiece(piece: GamePiece, action: GameAction.PieceShiftingAction): Option[OwnLegalityException] = 
    Option.unless(action.involvedPieces.contains(piece))
      {
        class Anon extends OwnLegalityException(s"This piece ${piece.prettyPrint} is not involved in action $action.")
        new Anon
      }

  def notAllPathsLeadToRome(paths: List[(Direction, Direction)]): Option[OwnLegalityException] =
      paths.map(_ + _).distinct match {
        case Nil => None
        case _ :: Nil => None
        case firstTarget :: secondTarget :: _ => 
          Some(NotAllPathsLeadToRome(firstTarget, secondTarget, paths))
      }
      
    

end OwnLegalityException
