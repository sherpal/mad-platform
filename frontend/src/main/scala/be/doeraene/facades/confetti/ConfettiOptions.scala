package be.doeraene.facades.confetti

import scala.scalajs.js
import scala.scalajs.js.`|`
import scala.scalajs.js.annotation.{JSBracketAccess, JSGlobal, JSGlobalScope, JSImport, JSName}

//noinspection ScalaDocUnknownTag
@js.native
trait ConfettiOptions extends js.Object {

  /** The angle in which to launch the confetti, in degrees. 90 is straight up.
    * @default
    *   90
    */
  var angle: js.UndefOr[Double] = js.native

  /** An array of color strings, in the HEX format... you know, like #bada55.
    */
  var colors: js.UndefOr[js.Array[String]] = js.native

  /** How quickly the confetti will lose speed. Keep this number between 0 and 1, otherwise the confetti will gain
    * speed. Better yet, just never change it.
    * @default
    *   0.9
    */
  var decay: js.UndefOr[Double] = js.native

  /** Disables confetti entirely for users that prefer reduced motion. The confetti() promise will resolve immediately
    * in this case.
    * @default
    *   false
    */
  var disableForReducedMotion: js.UndefOr[Boolean] = js.native

  /** How quickly the particles are pulled down. 1 is full gravity, 0.5 is half gravity, etc., but there are no limits.
    * @default
    *   1
    */
  var gravity: js.UndefOr[Double] = js.native

  /** Where to start firing confetti from. Feel free to launch off-screen if you'd like.
    */
  var origin: js.UndefOr[OriginConfetti] = js.native

  /** The number of confetti to launch. More is always fun... but be cool, there's a lot of math involved.
    * @default
    *   50
    */
  var particleCount: js.UndefOr[Double] = js.native

  /** Scale factor for each confetti particle. Use decimals to make the confetti smaller.
    * @default
    *   1
    */
  var scalar: js.UndefOr[Double] = js.native

  /** How far off center the confetti can go, in degrees. 45 means the confetti will launch at the defined angle plus or
    * minus 22.5 degrees.
    * @default
    *   45
    */
  var spread: js.UndefOr[Double] = js.native

  /** How fast the confetti will start going, in pixels.
    * @default
    *   45
    */
  var startVelocity: js.UndefOr[Double] = js.native

  /** How many times the confetti will move. This is abstract... but play with it if the confetti disappear too quickly
    * for you.
    * @default
    *   200
    */
  var ticks: js.UndefOr[Double] = js.native

  /** The confetti should be on top, after all. But if you have a crazy high page, you can set it even higher.
    * @default
    *   100
    */
  var zIndex: js.UndefOr[Double] = js.native
}
//noinspection MutatorLikeMethodIsParameterless
object ConfettiOptions {

  inline def apply(): ConfettiOptions = {
    val __obj = js.Dynamic.literal()
    __obj.asInstanceOf[ConfettiOptions]
  }

  // noinspection MutatorLikeMethodIsParameterless
  extension [Self <: ConfettiOptions](x: Self) {

    inline def duplicate: Self = js.Dynamic.global.Object.assign(js.Dynamic.literal(), x).asInstanceOf[Self]

    inline def combineWith[Other <: js.Any](other: Other): Self & Other =
      js.Dynamic.global.Object
        .assign(js.Dynamic.literal(), x, other.asInstanceOf[js.Any])
        .asInstanceOf[Self & Other]

    inline def set(key: String, value: js.Any): Self = {
      x.asInstanceOf[js.Dynamic].updateDynamic(key)(value)
      x
    }

    inline def setAngle(value: Double): Self = x.set("angle", value.asInstanceOf[js.Any])

    inline def deleteAngle: Self = x.set("angle", js.undefined)

    inline def setColorsVarargs(value: String*): Self = x.set("colors", js.Array(value*))

    inline def setColors(value: js.Array[String]): Self = x.set("colors", value.asInstanceOf[js.Any])

    inline def deleteColors: Self = x.set("colors", js.undefined)

    inline def setDecay(value: Double): Self = x.set("decay", value.asInstanceOf[js.Any])

    inline def deleteDecay: Self = x.set("decay", js.undefined)

    inline def setDisableForReducedMotion(value: Boolean): Self =
      x.set("disableForReducedMotion", value.asInstanceOf[js.Any])

    inline def deleteDisableForReducedMotion: Self = x.set("disableForReducedMotion", js.undefined)

    inline def setGravity(value: Double): Self = x.set("gravity", value.asInstanceOf[js.Any])

    inline def deleteGravity: Self = x.set("gravity", js.undefined)

    inline def setOrigin(value: OriginConfetti): Self = x.set("origin", value.asInstanceOf[js.Any])

    inline def deleteOrigin: Self = x.set("origin", js.undefined)

    inline def setParticleCount(value: Double): Self = x.set("particleCount", value.asInstanceOf[js.Any])

    inline def deleteParticleCount: Self = x.set("particleCount", js.undefined)

    inline def setScalar(value: Double): Self = x.set("scalar", value.asInstanceOf[js.Any])

    inline def deleteScalar: Self = x.set("scalar", js.undefined)

    inline def deleteShapes: Self = x.set("shapes", js.undefined)

    inline def setSpread(value: Double): Self = x.set("spread", value.asInstanceOf[js.Any])

    inline def deleteSpread: Self = x.set("spread", js.undefined)

    inline def setStartVelocity(value: Double): Self = x.set("startVelocity", value.asInstanceOf[js.Any])

    inline def deleteStartVelocity: Self = x.set("startVelocity", js.undefined)

    inline def setTicks(value: Double): Self = x.set("ticks", value.asInstanceOf[js.Any])

    inline def deleteTicks: Self = x.set("ticks", js.undefined)

    inline def setZIndex(value: Double): Self = x.set("zIndex", value.asInstanceOf[js.Any])

    inline def deleteZIndex: Self = x.set("zIndex", js.undefined)
  }
}
