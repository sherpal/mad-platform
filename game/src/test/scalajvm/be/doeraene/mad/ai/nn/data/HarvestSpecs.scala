package be.doeraene.mad.ai.nn.data

import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.GZIPInputStream

import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.nn.{ActionIndex, StateEncoder}
import be.doeraene.mad.game.GameBoundaries

/** Checks the harvest end of the pipeline, on the Scala side of the language boundary.
  *
  * The Python trainer reads these files by reinterpreting raw bytes, with only `manifest.json` to say
  * what the shapes are. There is no format to negotiate and therefore nothing that will fail loudly if
  * the two sides disagree - a transposed reshape or a wrong endianness would train perfectly happily on
  * scrambled boards. So the bytes are read back here, the way numpy will read them.
  */
final class HarvestSpecs extends munit.FunSuite:

  private val boundaries    = GameBoundaries.originalSixByFour
  private val featureLength = StateEncoder.featureLength(boundaries)
  private val policySize    = ActionIndex.teamSize

  private val tempDirectory = FunFixture[Path](
    setup = _ => Files.createTempDirectory("mad-harvest-"),
    teardown = directory =>
      Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  /** Reads a gzipped array of little-endian floats, the way `dataset.py` does. */
  private def readFloats(path: Path): Array[Float] =
    val bytes  = GZIPInputStream(Files.newInputStream(path)).readAllBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val values = new Array[Float](buffer.remaining())
    buffer.get(values)
    values

  private def readBytes(path: Path): Array[Byte] =
    GZIPInputStream(Files.newInputStream(path)).readAllBytes()

  private def sample(seed: Int): TrainingSample =
    TrainingSample(
      features = Array.tabulate(featureLength)(i => (seed * 1000 + i).toFloat),
      actionScores = Array.tabulate(policySize)(i => (seed * 10 + i).toFloat),
      legalActions = Array.tabulate(policySize)(i => if (i + seed) % 3 == 0 then 1.toByte else 0.toByte),
      rootScore = seed.toFloat * 0.5f,
      outcome = (seed % 3 - 1).toFloat
    )

  tempDirectory.test("shardsRoundTripThroughTheBytesPythonWillRead") { directory =>
    val samples = (0 until 10).map(sample).toVector
    val writer  = ShardWriter(directory, boundaries, samplesPerShard = 4)
    writer.addAll(samples)
    writer.close()

    // 10 samples at 4 per shard: three shards, the last one short.
    val shards = List("shard-0000", "shard-0001", "shard-0002")
    assertEquals(shards.count(name => Files.isDirectory(directory.resolve(name))), 3)

    val features = shards.flatMap(name => readFloats(directory.resolve(name).resolve("features.f32.gz")))
    val scores   = shards.flatMap(name => readFloats(directory.resolve(name).resolve("scores.f32.gz")))
    val legal    = shards.flatMap(name => readBytes(directory.resolve(name).resolve("legal.u8.gz")))
    val roots    = shards.flatMap(name => readFloats(directory.resolve(name).resolve("root.f32.gz")))
    val outcomes = shards.flatMap(name => readFloats(directory.resolve(name).resolve("outcome.f32.gz")))

    assertEquals(features, samples.flatMap(_.features.toList).toList)
    assertEquals(scores, samples.flatMap(_.actionScores.toList).toList)
    assertEquals(legal, samples.flatMap(_.legalActions.toList).toList)
    assertEquals(roots, samples.map(_.rootScore).toList)
    assertEquals(outcomes, samples.map(_.outcome).toList)
  }

  tempDirectory.test("manifestDescribesWhatWasWritten") { directory =>
    val writer = ShardWriter(directory, boundaries, samplesPerShard = 4)
    writer.addAll((0 until 6).map(sample))
    writer.close()

    val manifest = Files.readString(directory.resolve("manifest.json"))

    assert(manifest.contains("\"sampleCount\": 6"), manifest)
    assert(manifest.contains(s"\"featureLength\": $featureLength"), manifest)
    assert(manifest.contains(s"\"policySize\": $policySize"), manifest)
    assert(manifest.contains(s"\"planeCount\": ${StateEncoder.planeCount}"), manifest)
    // Ties the data to the ordering its labels mean something in.
    assert(manifest.contains(s"\"actionFingerprint\": ${ActionIndex.orderingFingerprint}"), manifest)
    assert(manifest.contains("{ \"name\": \"shard-0000\", \"samples\": 4 }"), manifest)
    assert(manifest.contains("{ \"name\": \"shard-0001\", \"samples\": 2 }"), manifest)
  }

  tempDirectory.test("harvestedPositionsAreWellFormed") { directory =>
    val writer = ShardWriter(directory, boundaries, samplesPerShard = 1 << 20)
    SupervisedHarvester.harvest(
      Player.tacticalTreeExplorer,
      boundaries,
      SupervisedHarvester.Config(games = 4, seed = 3, minimaxDepth = 1, gamesPerChunk = 4),
      writer
    )
    writer.close()

    assert(writer.written > 20, s"only ${writer.written} positions harvested")

    val legal    = readBytes(directory.resolve("shard-0000").resolve("legal.u8.gz")).grouped(policySize).toVector
    val scores   = readFloats(directory.resolve("shard-0000").resolve("scores.f32.gz")).grouped(policySize).toVector
    val roots    = readFloats(directory.resolve("shard-0000").resolve("root.f32.gz"))
    val outcomes = readFloats(directory.resolve("shard-0000").resolve("outcome.f32.gz"))

    assertEquals(legal.length, writer.written)
    assertEquals(roots.length, writer.written)

    legal.zipWithIndex.foreach { (mask, position) =>
      assert(mask.forall(flag => flag == 0 || flag == 1), s"position $position has a non-flag in its mask")
      assert(mask.count(_ == 1) > 0, s"position $position has no legal move")
    }

    // The root score has to be the best of the scored moves: it is the dense value target, and a value
    // head trained on something that is not the search's own opinion is not distilling anything.
    roots.zipWithIndex.foreach { (root, position) =>
      val legalScores = scores(position).zip(legal(position)).collect { case (score, 1) => score }
      assertEquals(root, legalScores.max, s"position $position")
    }

    assert(outcomes.forall(outcome => outcome == -1f || outcome == 0f || outcome == 1f), outcomes.toList.toString)
  }

  tempDirectory.test("harvestingIsReproducible") { directory =>
    // Games are played in parallel, so nothing about the order they finish in is fixed. Seeding per
    // game rather than per harvest is what keeps a run repeatable anyway, and a tuning or training
    // result that cannot be reproduced is not a result.
    def harvest(into: Path): Array[Byte] =
      val writer = ShardWriter(into, boundaries, samplesPerShard = 1 << 20)
      SupervisedHarvester.harvest(
        Player.tacticalTreeExplorer,
        boundaries,
        SupervisedHarvester.Config(games = 6, seed = 99, minimaxDepth = 1, gamesPerChunk = 3),
        writer
      )
      writer.close()
      Files.readAllBytes(into.resolve("shard-0000").resolve("features.f32.gz"))

    assertEquals(
      harvest(directory.resolve("first")).toVector,
      harvest(directory.resolve("second")).toVector
    )
  }

end HarvestSpecs
