package be.doeraene.perf

import be.doeraene.mad.ai.Player
import be.doeraene.mad.ai.benchmark.Benchmark

import com.sun.management.OperatingSystemMXBean

import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters.*

/** Deterministic timing harness for the search.
  *
  * Nothing in the engine is random, so a given (opening, depth) triple always replays the exact same game and explores
  * the exact same tree: the only thing that changes between two runs of this is how long the machine took to do it.
  * That is what makes it usable to compare two commits.
  *
  * The headline number is process CPU time rather than wall clock, for the reason given on [[Result]]'s
  * `cpuMillis`.
  */
object NatArrayBenchmark {

  /** @param cpuMillis
    *   CPU time of the whole JVM process, summed over every core. This, and not wall clock, is the number to compare
    *   two commits on: the search fans out over the machine at the root ([[be.doeraene.mad.ai.minimax.Node]]'s
    *   `actionsAndScores` maps the root's children in parallel), so wall clock measures how much of a shared machine
    *   the run happened to get, while total CPU measures the work it actually did. It is the same reason the
    *   per-thread clock is useless here: the measuring thread does almost none of the work.
    */
  final case class Result(cpuMillis: Long, wallMillis: Long, turns: Int, gcCount: Long, gcMillis: Long)

  private val operatingSystemMXBean =
    ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])

  private def median(values: List[Long]): Long = values.sorted.apply(values.size / 2)

  def main(args: Array[String]): Unit = {
    val depth    = if args.length > 0 then args(0).toInt else 3
    val openings = if args.length > 1 then args(1).toInt else 2
    val reps     = if args.length > 2 then args(2).toInt else 3

    val candidate = Player.tacticalPlayer(depth)
    val opponent  = Player.tacticalPlayer(depth)

    /* Wall clock on a shared machine moves with whatever else is running; the collector's own counters do not, and
     * they measure the thing actually under test here, which is how much garbage the search produces. */
    def gcCounters: (Long, Long) =
      val beans = ManagementFactory.getGarbageCollectorMXBeans.asScala
      (beans.map(_.getCollectionCount).sum, beans.map(_.getCollectionTime).sum)

    def run(games: List[Benchmark.BatteryGame]): Result = {
      val (gcCountBefore, gcTimeBefore) = gcCounters
      val cpuStart                      = operatingSystemMXBean.getProcessCpuTime
      val start                         = System.nanoTime()
      var turns                         = 0
      games.foreach(game => turns += Benchmark.playOne(candidate, opponent, game).turns)
      val elapsed                     = (System.nanoTime() - start) / 1000000L
      val cpu                         = (operatingSystemMXBean.getProcessCpuTime - cpuStart) / 1000000L
      val (gcCountAfter, gcTimeAfter) = gcCounters
      Result(cpu, elapsed, turns, gcCountAfter - gcCountBefore, gcTimeAfter - gcTimeBefore)
    }

    // warm up the JIT on openings that are not part of the measured set
    run(Benchmark.battery(1, 12345L, skip = 40))

    val games   = Benchmark.battery(openings, 42L)
    val results = (1 to reps).map(_ => run(games)).toList

    results.zipWithIndex.foreach((result, index) =>
      println(
        s"rep ${index + 1}: cpu=${result.cpuMillis} ms wall=${result.wallMillis} ms " +
          s"(turns=${result.turns}, gc=${result.gcCount} collections / ${result.gcMillis} ms)"
      )
    )
    println(
      s"BENCH depth=$depth openings=$openings games=${games.size} " +
        s"cpuBest=${results.map(_.cpuMillis).min}ms cpuMedian=${median(results.map(_.cpuMillis))}ms " +
        s"wallBest=${results.map(_.wallMillis).min}ms gcCollections=${results.map(_.gcCount).min}"
    )
  }

}
