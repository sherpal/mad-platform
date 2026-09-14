package be.doeraene.mad.ai

import be.doeraene.mad.ai.minimax.{Function1Like, Node, TreeExplorer}
import be.doeraene.mad.ai.minimax.Function1Like.act
import be.doeraene.mad.game.{GameAction, GameState, PieceEvaluator, Positions, Team}

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** A [[Player]] is a clever wrapper around a policy which decides what action to take, given the current game. */
final class Player[Game, Action](val name: Player.Name, policy: Game => Action) {

  def act(game: Game)(using Function1Like[Action, Game]): Game = policy(game).act(game)

  def nextAction(game: Game): Action = policy(game)

  def withName(newName: Player.Name): Player[Game, Action] = Player(newName, policy)

}

object Player {

  opaque type Name = String
  object Name:
    def apply(name: String): Name = name

  type MadPlayer = Player[GameState, GameAction]

  def timeIt[A](effect: => A): (A, FiniteDuration) =
    val startTime = LocalDateTime.now
    val a         = effect
    val endTime   = LocalDateTime.now
    val timeTaken = startTime.until(endTime, ChronoUnit.MILLIS)
    (a, timeTaken.millis)

  def randomMadPlayer: MadPlayer =
    Player(
      "Random",
      (gameState: GameState) => scala.util.Random.shuffle(gameState.allValidActions).head
    )

  /** More general version of the minimax mad player, where the [[TreeExplorer]] can each time depend on the current
    * [[GameState]].
    */
  def gameStateDependentMinimaxMadPlayer(
      minimaxDepth: Int,
      explorerFromGameState: GameState => TreeExplorer.MadTreeExplorer
  ): MadPlayer =
    Player(
      s"Minimax-$minimaxDepth",
      { (gs: GameState) =>
        given TreeExplorer[GameState, GameAction, Team] = explorerFromGameState(gs)
        Node.MadGameStateNode(gs).bestAction(gs.turnOfTeam, minimaxDepth)
      }
    )

  def jPaulDoeTheoryTreeExplorer(aValue: Double)(
      gameState: GameState
  ): TreeExplorer.MadTreeExplorer =
    val team                  = gameState.turnOfTeam
    val numberOfPiecesForTeam = gameState.pieces.keys.count(_.team == team.otherTeam)
    val pieceEvaluator = if numberOfPiecesForTeam >= 6 then
      val target = gameState.pieces.getOrElse(team.otherTeam._111, gameState.gameBoundaries.topLeft).asDoublePair
      PieceEvaluator.jPaulDoeTheoryWithTarget(aValue, target)
    else if numberOfPiecesForTeam <= 3 then PieceEvaluator.jPaulDoeFirstTheory(aValue)
    else
      val _111PosNow = gameState.pieces.getOrElse(team.otherTeam._111, gameState.gameBoundaries.topLeft).asDoublePair
      PieceEvaluator.jPaulDoeTheoryWithTarget(
        aValue,
        { (gameState, piece) =>
          val _111Fut    = piece.team.otherTeam._111
          val _111PosFut = gameState.pieces.getOrElse(_111Fut, gameState.gameBoundaries.topLeft).asDoublePair
          (
            (_111PosNow._1 * (numberOfPiecesForTeam - 3) + _111PosFut._1 * (6 - numberOfPiecesForTeam)) / 3,
            (_111PosNow._2 * (numberOfPiecesForTeam - 3) + _111PosFut._2 * (6 - numberOfPiecesForTeam)) / 3
          )
        }
      )
    TreeExplorer.MadGameStateTreeExplorer(Node.evaluatorFromPieceEvaluator(pieceEvaluator))

  /** Returns a minimax [[MadPlayer]] from a theory crafted by JPaul. The [[PieceEvaluator]] depends on the current
    * [[GameState]], and in particular depends on the position of the 111 ennemy and the number of pieces that the
    * ennemy has.
    */
  def jPaulTheoryPlayer(minimaxDepth: Int, aValue: Double): MadPlayer =
    gameStateDependentMinimaxMadPlayer(
      minimaxDepth,
      jPaulDoeTheoryTreeExplorer(aValue)
    )

  /** [[TreeExplorer]] wrapping [[ClaudeEvaluator]]. It doesn't need to depend on the current [[GameState]] (unlike
    * jPaul's, which picks a different [[PieceEvaluator]] depending on the piece count), since the phase-scaling is
    * done inside the evaluator itself.
    */
  def claudeTheoryTreeExplorer: TreeExplorer.MadTreeExplorer =
    TreeExplorer.MadGameStateTreeExplorer(ClaudeEvaluator.evaluate)

  /** Returns a minimax [[MadPlayer]] from [[ClaudeEvaluator]]: a material scale built from the (attack, defence)
    * combat tier rather than the corvette/frigate/destroyer/cruiser naming order, a recall-aware correction for
    * exiled-but-recallable pieces, a heavily-weighted 111 safety term, a phase-scaled 222 caution term, and a small
    * centre-control term. See [[ClaudeEvaluator]] for the full reasoning.
    */
  def claudeTheoryPlayer(minimaxDepth: Int): MadPlayer =
    gameStateDependentMinimaxMadPlayer(minimaxDepth, _ => claudeTheoryTreeExplorer)

  /** Same as [[claudeTheoryPlayer]], but with an explicit [[ClaudeWeights]] instead of the hand-picked default - what
    * [[be.doeraene.mad.ai.tuning.ClaudeWeightTuner]] uses to score candidates.
    */
  def claudeTheoryPlayerWithWeights(minimaxDepth: Int, weights: ClaudeWeights): MadPlayer =
    gameStateDependentMinimaxMadPlayer(
      minimaxDepth,
      _ => TreeExplorer.MadGameStateTreeExplorer(ClaudeEvaluator.evaluate(weights))
    )

  /** Returns a minimax [[MadPlayer]] from [[TacticalEvaluator]]: exchange-aware threat detection over every ship
    * (not just 111 and 222), tempo-awareness at the leaf, a swap-based model of what recalling an exiled ship buys,
    * and safe-escape counting for the corvette. See [[TacticalEvaluator]] for the full reasoning.
    */
  def tacticalPlayer(minimaxDepth: Int): MadPlayer =
    gameStateDependentMinimaxMadPlayer(minimaxDepth, _ => tacticalTreeExplorer)

  /** [[TreeExplorer]] wrapping [[TacticalEvaluator]] at its default weights - the entry point for callers that drive
    * the search themselves rather than through a [[MadPlayer]], such as the web worker.
    *
    * A `val`, unlike [[claudeTheoryTreeExplorer]]: [[TacticalEvaluator.evaluate]] precomputes a set of tables from
    * the weights it is given, and rebuilding those per call would throw away the point of having them.
    */
  val tacticalTreeExplorer: TreeExplorer.MadTreeExplorer =
    TreeExplorer.MadGameStateTreeExplorer(TacticalEvaluator.evaluate(TacticalWeights.default))

  /** Same as [[tacticalPlayer]], but with explicit [[TacticalWeights]] - what a tuner scores candidates with. */
  def tacticalPlayerWithWeights(minimaxDepth: Int, weights: TacticalWeights): MadPlayer =
    /* Built once and reused for every move of the game on purpose: `TacticalEvaluator.evaluate` precomputes a set of
     * per-weights tables, and `gameStateDependentMinimaxMadPlayer` calls this function again at every single turn. */
    val explorer = TreeExplorer.MadGameStateTreeExplorer(TacticalEvaluator.evaluate(weights))
    gameStateDependentMinimaxMadPlayer(minimaxDepth, _ => explorer)

  def minimaxMadPlayer(minimaxDepth: Int)(using
      treeExplorer: TreeExplorer[GameState, GameAction, Team]
  ): MadPlayer =
    gameStateDependentMinimaxMadPlayer(minimaxDepth, _ => treeExplorer)

  /** Plays a game of Mad with that red and blue players.
    *
    * @param fromGameState
    *   [[GameState]] to start the game from.
    * @return
    *   the history of game states
    */
  def playMadGame(
      redPlayer: MadPlayer,
      bluePlayer: MadPlayer,
      fromGameState: GameState,
      verbose: Boolean = true
  ): List[GameState] = {
    val putStrLn = if verbose then (str: String) => println(str) else (str: String) => ()

    @tailrec
    def play(
        gameState: GameState,
        history: List[GameState]
    ): (Option[Team], List[GameState]) =
      if gameState.ended then (gameState.maybeWinner, history)
      else
        val teamTurn = gameState.turnOfTeam
        val player   = if teamTurn == Team.Red then redPlayer else bluePlayer
        putStrLn("*" * 60)
        putStrLn(
          s"New turn (Turn number: ${gameState.turnNumber}, time since last died: ${gameState.turnsSinceLastPieceDied})"
        )
        putStrLn(s"Turn of $teamTurn. Game state is")
        putStrLn(gameState.prettyPrint)
        val (action, timeTaken) = timeIt(player.nextAction(gameState))
        putStrLn(s"It took ${timeTaken.toSeconds} seconds to decide.")
        putStrLn(s"Action selected: ${action.prettyPrint(gameState)}")
        val newGameState = action(gameState)
        play(newGameState, history :+ newGameState)

    play(fromGameState, fromGameState :: Nil) match {
      case (Some(value), history) =>
        putStrLn(s"Winner is $value")
        history
      case (None, history) =>
        putStrLn("It's a tie!")
        history
    }
  }

}
