package be.doeraene.facades.confetti

import scala.scalajs.js
import scala.scalajs.js.`|`
import scala.scalajs.js.annotation.{JSBracketAccess, JSGlobal, JSGlobalScope, JSImport, JSName}

@js.native
trait OriginConfetti extends js.Object {

  /** The x position on the page, with 0 being the left edge and 1 being the right edge.
    * @default
    *   0.5
    */
  var x: js.UndefOr[Double] = js.native

  /** The y position on the page, with 0 being the left edge and 1 being the right edge.
    * @default
    *   0.5
    */
  var y: js.UndefOr[Double] = js.native
}
//noinspection MutatorLikeMethodIsParameterless
object OriginConfetti {

  inline def apply(): OriginConfetti = {
    val __obj = js.Dynamic.literal()
    __obj.asInstanceOf[OriginConfetti]
  }

  extension [Self <: OriginConfetti](x: Self) {

    inline def duplicate: Self = js.Dynamic.global.Object.assign(js.Dynamic.literal(), x).asInstanceOf[Self]

    inline def combineWith[Other <: js.Any](other: Other): Self & Other =
      js.Dynamic.global.Object
        .assign(js.Dynamic.literal(), x, other.asInstanceOf[js.Any])
        .asInstanceOf[Self & Other]

    inline def set(key: String, value: js.Any): Self = {
      x.asInstanceOf[js.Dynamic].updateDynamic(key)(value)
      x
    }

    inline def setX(value: Double): Self = x.set("x", value.asInstanceOf[js.Any])

    inline def deleteX: Self = x.set("x", js.undefined)

    inline def setY(value: Double): Self = x.set("y", value.asInstanceOf[js.Any])

    inline def deleteY: Self = x.set("y", js.undefined)
  }
}
