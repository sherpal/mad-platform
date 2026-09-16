package be.doeraene.mad.ai.minimax

import be.doeraene.mad.game.{GameAction, GamePiece, GameState, PieceEvaluator, Team}
import be.doeraene.perf.{NatArray, ParColIfPossible}

import scala.reflect.ClassTag

trait Node[T, Action, Turn]:

  def t: T

  inline final def turn(using treeExplorer: TreeExplorer[T, Action, Turn]): Turn = treeExplorer.turnOf(t)

  inline final def actions(using treeExplorer: TreeExplorer[T, Action, Turn]): NatArray[Action] =
    treeExplorer.actions(t)

  /** Computes the score of the node, seen from the eyes of the specified turn. */
  inline final def score(forTurn: Turn)(using treeExplorer: TreeExplorer[T, Action, Turn]): Double =
    treeExplorer.score(t, forTurn)

  inline final def exactScore(forTurn: Turn)(using treeExplorer: TreeExplorer[T, Action, Turn]): Double =
    treeExplorer.exactScore(t, forTurn)

  inline final def exactSolutionIsKnown(using treeExplorer: TreeExplorer[T, Action, Turn]): Boolean =
    treeExplorer.exactSolutionIsKnown(t)

  inline final def isTerminalNode(using treeExplorer: TreeExplorer[T, Action, Turn]): Boolean =
    treeExplorer.isTerminalNode(t)

  /** Returns a `List` rather than a `Map` on purpose: [[scoreForAction]]'s alpha-beta loop drains this via repeated
    * `.head`/`.tail`, which is O(1) per step on a `List` but O(log n) per step on an immutable `Map` (`tail` has to
    * rebuild the underlying hash trie), making a full drain O(n log n) instead of O(n) - paid at every node, every ply,
    * of the whole search tree. Nothing here needs key lookup, only sequential draining, so `List` is strictly the right
    * type, not just a faster one.
    */
  final def children(using treeExplorer: TreeExplorer[T, Action, Turn])(using
      ClassTag[Action]
  ): NatArray[(Action, Node[T, Action, Turn])] =
    val parent = t

    /* Both of the following serve the alpha-beta loop in `scoreForAction`, which drains this list left to right and
     * abandons the rest at the first cutoff.
     *
     * Ordering: a cutoff happens when a move turns out to be good enough to refute the whole branch, so trying the
     * moves most likely to be that one first is what makes the pruning actually prune. Captures are the obvious
     * candidates here, and `actionBonus` already knows which those are. A partition rather than a sort: it is one
     * pass, it is stable, and every capture is as good a candidate as any other, so there is nothing to rank.
     *
     * Laziness: building a child's `GameState` means rebuilding a piece map, and the whole point of a cutoff is that
     * the remaining children are never looked at. Constructing their states eagerly paid that cost for every single
     * one of them, cutoff or not. */
    val (capturing, quiet) = actions.partition(treeExplorer.actionBonus(_, parent) > 0.0)

    (capturing ++ quiet).map { action =>
      action -> new Node[T, Action, Turn] {
        lazy val t: T = treeExplorer.actionIsLikeFunction1.asFunction1(action).apply(parent)
      }
    }

  def scoreForAction(action: Action, childAfterAction: Node[T, Action, Turn], turn: Turn, maxDepth: Int)(using
      treeExplorer: TreeExplorer[T, Action, Turn]
  )(using
      ClassTag[Action]
  ): Double = {
    def alphaBeta(node: Node[T, Action, Turn], currentDepth: Int, alpha: Double, beta: Double): Double =
      if node.isTerminalNode then node.exactScore(turn) * (currentDepth + 1)
      else if node.exactSolutionIsKnown then node.exactScore(turn)
      else if currentDepth == 0 then node.score(turn)
      else if turn == node.turn then // this is the maximizing player
        var value: Double = Double.MinValue
        var currentAlpha  = alpha
        var nextNodes     = node.children
        var continue      = true
        while continue && nextNodes.nonEmpty do
          val (_, child)    = nextNodes.head
          val valueForChild = alphaBeta(child, currentDepth - 1, currentAlpha, beta)
          value = value max valueForChild
          currentAlpha = currentAlpha max value

          if currentAlpha >= beta then continue = false

          nextNodes = nextNodes.tail
        value
      else
        var value: Double = Double.MaxValue
        var currentBeta   = beta
        var nextNodes     = node.children
        var continue      = true
        while continue && nextNodes.nonEmpty do
          val (_, child)    = nextNodes.head
          val valueForChild = alphaBeta(child, currentDepth - 1, alpha, currentBeta)
          value = value min valueForChild
          currentBeta = currentBeta min value

          if currentBeta <= alpha then continue = false

          nextNodes = nextNodes.tail

        value

    val child     = childAfterAction
    val extraTurn = treeExplorer.extraTurnsForAction(action, t, child.t)
    alphaBeta(child, maxDepth + extraTurn, Double.MinValue, Double.MaxValue)
  }

  def actionsAndScores(turn: Turn, maxDepth: Int = 5)(using
      treeExplorer: TreeExplorer[T, Action, Turn]
  )(using
      ClassTag[Action]
  ): NatArray[(Action, Double)] =
    children.par.map((action, child) => (action, scoreForAction(action, child, turn, maxDepth))).toNatArray

  def bestAction(turn: Turn, maxDepth: Int = 5, verbose: Boolean = false)(using
      treeExplorer: TreeExplorer[T, Action, Turn]
  )(using
      ClassTag[Action]
  ): Action = {
    val actionScores = actionsAndScores(turn, maxDepth)
      .sortBy((action, _) => -treeExplorer.actionBonus(action, t))
    if verbose then println(actionScores.map((action, score) => s"$action: $score").mkString("\n"))
    actionScores.maxBy(_._2)._1
  }

end Node

object Node:

  /** Evaluates a game state T from the point of view of player Turn */
  type Evaluator[T, Turn]   = (T, Turn) => Double
  type TurnOf[T, Turn]      = T => Turn
  type ActionFor[T, Action] = T => List[Action]
  type IsTerminalNode[T]    = T => Boolean

  val infinity: 1000000.0 = 1000000.0

  def madGameStateEvaluator: Evaluator[GameState, Team] =
    (gameState: GameState, team: Team) =>
      gameState.pieces.keys
        .filter(_.team == team)
        .map(piece => piece.pieceTakeScore(gameState) - piece.piecesTakenScore(gameState))
        .sum

  def numberOfPiecesEvaluator: Evaluator[GameState, Team] =
    (gameState: GameState, team: Team) =>
      val (myPieces, theirPieces) = gameState.pieces.keys.partition(_.team == Team)
      (myPieces.size - theirPieces.size) - gameState.turnsSinceLastPieceDied

  def evaluatorFromPieceEvaluator(
      pieceEvaluator: PieceEvaluator
  ): Evaluator[GameState, Team] =
    (gameState: GameState, team: Team) =>
      val (myPieces, theirPieces) = gameState.pieces.keys.partition(_.team == team)
      myPieces.map(pieceEvaluator.pieceValue(_, gameState)).sum -
        theirPieces.map(pieceEvaluator.pieceValue(_, gameState)).sum

  final class MadGameStateNode(gameState: GameState) extends Node[GameState, GameAction, Team]:
    def t: GameState = gameState

end Node
