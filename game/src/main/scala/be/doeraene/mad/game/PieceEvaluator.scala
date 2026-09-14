package be.doeraene.mad.game

trait PieceEvaluator:

  /** Describes how this [[GamePiece]] is evaluated (bigger is better). */
  def pieceValue(piece: GamePiece, gameState: GameState): Double

  def +(that: PieceEvaluator): PieceEvaluator =
    PieceEvaluator.factory((piece, gs) => this.pieceValue(piece, gs) + that.pieceValue(piece, gs))

end PieceEvaluator

object PieceEvaluator:

  private def factory(evaluator: (GamePiece, GameState) => Double): PieceEvaluator = new PieceEvaluator {
    def pieceValue(piece: GamePiece, gameState: GameState): Double = evaluator(piece, gameState)
  }

  private def factoryWithoutGameState(evaluator: GamePiece => Double): PieceEvaluator = new PieceEvaluator {
    def pieceValue(piece: GamePiece, gameState: GameState): Double = evaluator(piece)
  }

  def constant: PieceEvaluator                                     = factoryWithoutGameState(_ => 1)
  def fromMap(map: Map[GamePiece, Double]): PieceEvaluator         = factoryWithoutGameState(map)
  def fromFunction(evaluator: GamePiece => Double): PieceEvaluator = factoryWithoutGameState(evaluator)

  def fromGameStateDependentFunction(evaluator: (GamePiece, GameState) => Double): PieceEvaluator = factory(
    evaluator
  )

  def fromStatCoefficient(
      movementMultiplier: Double,
      attackMultiplier: Double,
      defenceMultiplier: Double
  ): PieceEvaluator =
    fromFunction { piece =>
      val (movement, attack, defence) = piece.statValues
      movement * movementMultiplier + attack * attackMultiplier + defence * defenceMultiplier
    }

  def statsSum: PieceEvaluator = fromStatCoefficient(1, 1, 1)

  def jPaulDoeTheoryWithTarget(aValue: Double, target: (Double, Double)): PieceEvaluator =
    jPaulDoeTheoryWithTarget(aValue, (_, _) => target)

  def jPaulDoeTheoryWithTarget(
      aValue: Double,
      target: (GameState, GamePiece) => (Double, Double)
  ): PieceEvaluator =
    fromGameStateDependentFunction { (piece, gameState) =>
      piece.pieceValue - piece.pieceValue * piece.turnToGoTo(target(gameState, piece), gameState) / gameState
        .materialScore(piece.team) / 50
    }

  /** [[PieceEvaluator]] with target, where the target is the opponent 111.
    */
  def jPaulDoeFirstTheory(aValue: Double): PieceEvaluator =
    jPaulDoeTheoryWithTarget(
      aValue,
      (gameState, piece) => gameState.pieces.get(piece.opponent111).fold((0.0, 0.0))(_.asDoublePair)
    )

end PieceEvaluator
