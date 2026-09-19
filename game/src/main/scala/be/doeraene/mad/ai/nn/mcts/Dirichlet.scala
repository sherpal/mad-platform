package be.doeraene.mad.ai.nn.mcts

import scala.util.Random

/** Dirichlet samples, for the exploration noise self-play mixes into the root priors.
  *
  * Without it self-play is deterministic given a network, so every game from a given opening is the
  * same game, and the network only ever sees positions it already prefers. The noise is what makes it
  * try the move it has written off - which is the only way a policy that is confidently wrong ever finds
  * out.
  *
  * Written out by hand because this has to run in the browser too, and there is no cross-platform maths
  * library in the build to lean on. It is about twenty lines, so that is a better trade than a
  * dependency that may or may not link under Scala.js.
  */
object Dirichlet:

  /** A draw from Dir(alpha, ..., alpha) with `size` components: `size` independent Gamma(alpha) samples,
    * normalised.
    */
  def symmetric(size: Int, alpha: Double, random: Random): Array[Double] =
    val sample = new Array[Double](size)
    var total  = 0.0
    var index  = 0
    while index < size do
      val drawn = gamma(alpha, random)
      sample(index) = drawn
      total += drawn
      index += 1

    if total <= 0.0 then
      // Every draw underflowed, which a very small alpha can manage. Uniform is the honest answer.
      java.util.Arrays.fill(sample, 1.0 / size)
    else
      index = 0
      while index < size do
        sample(index) = sample(index) / total
        index += 1
    sample

  /** Marsaglia and Tsang's method.
    *
    * Their squeeze only covers shape >= 1, and the alpha worth using here is well below that - the whole
    * point of the noise is to be lumpy, to favour a few moves strongly rather than lift all of them
    * evenly. The standard fix is the boost identity: a Gamma(a) is a Gamma(a+1) scaled by U^(1/a).
    */
  private def gamma(shape: Double, random: Random): Double =
    if shape < 1.0 then
      val boosted = gamma(shape + 1.0, random)
      boosted * math.pow(random.nextDouble(), 1.0 / shape)
    else
      val d = shape - 1.0 / 3.0
      val c = 1.0 / math.sqrt(9.0 * d)
      var result = 0.0
      var drawing = true
      while drawing do
        var v = 0.0
        var x = 0.0
        while v <= 0.0 do
          x = random.nextGaussian()
          v = 1.0 + c * x
        v = v * v * v
        val u = random.nextDouble()
        if u < 1.0 - 0.0331 * x * x * x * x then
          result = d * v
          drawing = false
        else if math.log(u) < 0.5 * x * x + d * (1.0 - v + math.log(v)) then
          result = d * v
          drawing = false
      result

end Dirichlet
