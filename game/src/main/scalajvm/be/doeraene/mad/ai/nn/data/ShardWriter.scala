package be.doeraene.mad.ai.nn.data

import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.GZIPOutputStream

import be.doeraene.mad.ai.nn.{ActionIndex, StateEncoder}
import be.doeraene.mad.game.{GameBoundaries, GamePiece}

/** One labelled position, already encoded.
  *
  * @param features
  *   what [[StateEncoder]] produced for the position, from the mover's point of view.
  * @param actionScores
  *   the search's score for each of the mover's actions, indexed the way a policy head is (see
  *   [[be.doeraene.mad.ai.nn.Canonical.policyIndex]]). Raw minimax scores, not a distribution: turning them into one
  *   needs a temperature, and that is a training hyperparameter rather than something to bake into the data.
  * @param legalActions
  *   `1` where [[actionScores]] means something. Entries for illegal actions are unspecified.
  * @param rootScore
  *   the search's score for the position itself, i.e. the best of [[actionScores]]. A dense value target, as against
  *   the sparse one in [[outcome]].
  * @param outcome
  *   how the game this position came from ended, from the mover's point of view: `1`, `0` or `-1`.
  */
final case class TrainingSample(
    features: Array[Float],
    actionScores: Array[Float],
    legalActions: Array[Byte],
    rootScore: Float,
    outcome: Float
)

/** Writes labelled positions out as flat binary arrays for the Python trainer to pick up.
  *
  * Deliberately not a self-describing format: each array is written as raw little-endian values, gzipped, so the Python
  * side is `np.frombuffer(gzip.open(path).read(), dtype)` and nothing else - no parser to keep in step on two sides of
  * a language boundary. The shapes live in `manifest.json` next to the shards.
  *
  * Gzip rather than plain floats because the feature planes are one-hot and mostly zero; it costs nothing on the write
  * side and turns a ~2.4 KB position into a few hundred bytes.
  *
  * The manifest also records [[ActionIndex.orderingFingerprint]]. A dataset harvested before an ordering change and a
  * model trained after one would agree on every shape and disagree on every meaning, so the two need to be tied
  * together by something.
  */
final class ShardWriter(
    root: Path,
    boundaries: GameBoundaries,
    samplesPerShard: Int
):

  private val featureLength = StateEncoder.featureLength(boundaries)
  private val policySize    = ActionIndex.teamSize

  private var shard: Option[ShardWriter.OpenShard] = None
  private var shardSizes: Vector[Int]              = Vector.empty
  private var totalWritten: Int                    = 0

  Files.createDirectories(root)

  def written: Int = totalWritten

  def add(sample: TrainingSample): Unit =
    require(sample.features.length == featureLength, s"expected $featureLength features")
    require(sample.actionScores.length == policySize, s"expected $policySize action scores")
    require(sample.legalActions.length == policySize, s"expected $policySize legality flags")

    val open = shard.getOrElse {
      val fresh = ShardWriter.OpenShard(root.resolve(f"shard-${shardSizes.length}%04d"))
      shard = Some(fresh)
      fresh
    }

    open.features.putFloats(sample.features)
    open.scores.putFloats(sample.actionScores)
    open.legal.putBytes(sample.legalActions)
    open.rootScore.putFloat(sample.rootScore)
    open.outcome.putFloat(sample.outcome)
    open.samples += 1
    totalWritten += 1

    if open.samples >= samplesPerShard then closeShard()

  def addAll(samples: IterableOnce[TrainingSample]): Unit = samples.iterator.foreach(add)

  private def closeShard(): Unit = shard.foreach { open =>
    open.close()
    shardSizes = shardSizes :+ open.samples
    shard = None
  }

  /** Flushes the shard in progress and writes the manifest. */
  def close(): Unit =
    closeShard()
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))

  private def manifest: String =
    val shards = shardSizes.zipWithIndex
      .map((samples, index) => f"""    { "name": "shard-$index%04d", "samples": $samples }""")
      .mkString(",\n")
    s"""{
       |  "gameType": "${boundaries.gameType.value}",
       |  "rows": ${boundaries.lastRow},
       |  "cols": ${boundaries.lastCol},
       |  "planeCount": ${StateEncoder.planeCount},
       |  "piecesPerTeam": ${GamePiece.piecesPerTeam},
       |  "featureLength": $featureLength,
       |  "policySize": $policySize,
       |  "actionFingerprint": ${ActionIndex.orderingFingerprint},
       |  "sampleCount": $totalWritten,
       |  "arrays": {
       |    "features": { "file": "features.f32.gz", "dtype": "<f4", "shape": [$featureLength] },
       |    "scores": { "file": "scores.f32.gz", "dtype": "<f4", "shape": [$policySize] },
       |    "legal": { "file": "legal.u8.gz", "dtype": "u1", "shape": [$policySize] },
       |    "rootScore": { "file": "root.f32.gz", "dtype": "<f4", "shape": [] },
       |    "outcome": { "file": "outcome.f32.gz", "dtype": "<f4", "shape": [] }
       |  },
       |  "shards": [
       |$shards
       |  ]
       |}
       |""".stripMargin

end ShardWriter

object ShardWriter:

  /** A gzipped stream of raw little-endian values.
    *
    * Little-endian because that is what numpy calls native on every machine this will run on; writing big-endian (which
    * is what `DataOutputStream` would give) would mean remembering to say so on the Python side forever.
    */
  private final class Sink(path: Path):
    private val out    = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(path)), 1 << 16)
    private var buffer = ByteBuffer.allocate(1 << 12).order(ByteOrder.LITTLE_ENDIAN)

    private def ensure(bytes: Int): Unit =
      if buffer.capacity < bytes then buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
      buffer.clear()

    def putFloats(values: Array[Float]): Unit =
      ensure(values.length * 4)
      buffer.asFloatBuffer().put(values)
      out.write(buffer.array(), 0, values.length * 4)

    def putFloat(value: Float): Unit =
      ensure(4)
      buffer.putFloat(value)
      out.write(buffer.array(), 0, 4)

    def putBytes(values: Array[Byte]): Unit = out.write(values)

    def close(): Unit = out.close()
  end Sink

  private final class OpenShard(directory: Path):
    Files.createDirectories(directory)

    val features: Sink  = Sink(directory.resolve("features.f32.gz"))
    val scores: Sink    = Sink(directory.resolve("scores.f32.gz"))
    val legal: Sink     = Sink(directory.resolve("legal.u8.gz"))
    val rootScore: Sink = Sink(directory.resolve("root.f32.gz"))
    val outcome: Sink   = Sink(directory.resolve("outcome.f32.gz"))

    var samples: Int = 0

    def close(): Unit = List(features, scores, legal, rootScore, outcome).foreach(_.close())
  end OpenShard

end ShardWriter
