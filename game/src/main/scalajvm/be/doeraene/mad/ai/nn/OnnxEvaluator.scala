package be.doeraene.mad.ai.nn

import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import be.doeraene.mad.ai.nn.mcts.{BatchEvaluator, Evaluation}
import be.doeraene.mad.game.{GameBoundaries, GameState}
import be.doeraene.perf.NatArray

/** Runs an exported network on the JVM, for self-play and for benchmarking against the minimax.
  *
  * The browser runs the very same `.onnx` file through onnxruntime-web, so there is one model and one
  * implementation of it rather than two that can drift.
  *
  * @param intraOpThreads
  *   threads ONNX Runtime may use inside a single evaluation. One by default, and that is not a
  *   conservative guess: every caller here plays whole games in parallel already, so letting each
  *   session also fan out across all cores just has them fighting each other. The board is 6x4 - there
  *   is not enough work in one evaluation to be worth splitting.
  */
final class OnnxEvaluator(
    modelPath: Path,
    boundaries: GameBoundaries,
    intraOpThreads: Int = 1
) extends BatchEvaluator
    with AutoCloseable:

  private val featureLength = StateEncoder.featureLength(boundaries)
  private val environment   = OrtEnvironment.getEnvironment

  OnnxEvaluator.checkOrdering(modelPath)

  private val session =
    val options = OrtSession.SessionOptions()
    options.setIntraOpNumThreads(intraOpThreads)
    environment.createSession(modelPath.toString, options)

  def evaluate(states: NatArray[GameState]): NatArray[Evaluation] =
    val batch = states.length
    if batch == 0 then NatArray.empty[Evaluation]
    else
      /* Allocated per call rather than reused. Benchmarks play games in parallel and share one
       * evaluator between them, so a buffer hanging off this object would be a data race - and at a few
       * tens of kilobytes a batch there is nothing here worth the trouble of a thread-local. */
      val features = new Array[Float](batch * featureLength)
      var index    = 0
      while index < batch do
        StateEncoder.encodeInto(states(index), features, index * featureLength)
        index += 1

      val shape = Array[Long](batch, StateEncoder.planeCount, boundaries.lastRow, boundaries.lastCol)
      val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), shape)
      try
        val outputs = session.run(Map("board" -> input).asJava)
        try
          val policy = outputs.get(0).getValue.asInstanceOf[Array[Array[Float]]]
          val value  = outputs.get(1).getValue.asInstanceOf[Array[Float]]
          Array.tabulate(batch)(row => Evaluation(policy(row), value(row)))
        finally outputs.close()
      finally input.close()

  def close(): Unit = session.close()

end OnnxEvaluator

object OnnxEvaluator:

  /** Refuses a model that was trained against a different action ordering.
    *
    * The one failure this whole fingerprint mechanism exists for. A model from before an ordering change
    * has exactly the right shapes and entirely the wrong meanings: every policy output points at a
    * different move, and nothing about that is visible except an engine that plays like nonsense. The
    * export writes the fingerprint next to the model so it can be checked rather than hoped for.
    */
  private def checkOrdering(modelPath: Path): Unit =
    val sidecar = Path.of(modelPath.toString.stripSuffix(".onnx") + ".json")
    if !Files.exists(sidecar) then
      throw IllegalArgumentException(
        s"$sidecar is missing; it is written by export_onnx.py and says which action ordering the " +
          "model's policy head was trained against"
      )

    val metadata = Files.readString(sidecar, StandardCharsets.UTF_8)
    val declared = io.circe.parser
      .parse(metadata)
      .toOption
      .flatMap(_.hcursor.get[Int]("actionFingerprint").toOption)
      .getOrElse(throw IllegalArgumentException(s"$sidecar has no actionFingerprint"))

    if declared != ActionIndex.orderingFingerprint then
      throw IllegalArgumentException(
        s"$modelPath was trained against action ordering $declared, but this build's ordering is " +
          s"${ActionIndex.orderingFingerprint}. Every policy index means a different move; retrain or " +
          "check out the revision the model was trained on."
      )

end OnnxEvaluator
