package be.doeraene.facades.confetti

import org.scalajs.dom

import scala.concurrent.duration._

object confetti {

  private def defaultOptions =
    ConfettiOptions()
      .setStartVelocity(30)
      .setSpread(360)
      .setTicks(60)
      .setZIndex(5)

  private def optionsWithXBetween(particleCount: Double, minX: Double, maxX: Double) =
    defaultOptions
      .setParticleCount(particleCount)
      .setOrigin(OriginConfetti().setX(scala.util.Random.between(minX, maxX)).setY(scala.util.Random.nextDouble() - 0.2))

  def firworks(): Unit = {
    val startTime = System.currentTimeMillis()
    val duration = 15000L // millis
    val animationEnd = startTime + duration

    val rate = 250.millis

    def loop(): Unit = {
      val currentTime = System.currentTimeMillis()
      if currentTime < animationEnd then {
        val timeLeft = animationEnd - currentTime

        val particleCount = 50.0 * (timeLeft.toDouble / duration)

        (dom.window: WindowConfetti).confetti(optionsWithXBetween(particleCount, 0.1, 0.3))
        (dom.window: WindowConfetti).confetti(optionsWithXBetween(particleCount, 0.7, 0.9))

        scala.scalajs.js.timers.setTimeout(rate)(loop())
      }
    }

    loop()
  }

}
