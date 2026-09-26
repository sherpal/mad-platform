package be.doeraene.workers

import be.doeraene.mad.game.GameBoundaries
import be.doeraene.mad.game.GameBoundaries.GameType

/** Which boards have a trained network shipped with the site, and what its file is called.
  *
  * A network's input is 19 x rows x cols and its heads end in a Linear over rows * cols cells, so the
  * board is baked into the weights: every board needs its own model, and a model handed the wrong board
  * is rejected by onnxruntime rather than merely playing badly.
  *
  * This lives in `shared-js` because both sides of the worker boundary need the same answer. The
  * frontend asks it whether to offer the network at all; the worker asks it which file to load. Two
  * separate lists would eventually disagree, and the symptom would be an engine that is offered and
  * then fails.
  */
object NeuralModels:

  /** Board to the base name of its model, under `nn/` next to the site. */
  private val fileNames: Map[GameType, String] = Map(
    GameBoundaries._6by4 -> "mad-6x4",
    GameBoundaries._5by5 -> "mad-5x5"
  )

  /** Whether a network exists for this board. 4x6 and the Aztec Diamond have none yet: nothing is wrong
    * with them, they simply have not been trained, which is a day of harvesting and self-play each.
    */
  def isTrained(gameType: GameType): Boolean = fileNames.contains(gameType)

  def trainedBoards: Set[GameType] = fileNames.keySet

  /** Path of the model for this board, relative to the site root. */
  def modelPath(gameType: GameType): Option[String] = fileNames.get(gameType).map(name => s"nn/$name.onnx")

end NeuralModels
