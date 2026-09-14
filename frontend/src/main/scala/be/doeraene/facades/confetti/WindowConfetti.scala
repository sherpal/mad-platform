package be.doeraene.facades.confetti

import scala.language.implicitConversions

import org.scalajs.dom
import scala.scalajs.js

@js.native
trait WindowConfetti extends js.Object {

  def confetti(options: ConfettiOptions): Unit = js.native

}

object WindowConfetti {

  given Conversion[dom.Window, WindowConfetti] = _.asInstanceOf[WindowConfetti]

}
