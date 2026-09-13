package be.doeraene.components.router

import com.raquo.laminar.api.L._
import org.scalajs.dom
import org.scalajs.dom.PopStateEvent

import scala.concurrent.duration._
import scala.scalajs.js.timers.setTimeout

final class Router private () {

  import Router.Url

  def url: String = dom.window.location.href

  private lazy val currentUrl: Var[Url] = Var(url)

  private def trigger(): Unit =
    currentUrl.update(_ => url)

  dom.window.addEventListener("popstate", (_: PopStateEvent) => trigger())

  def moveTo(url: String): Unit = {
    dom.window.history
      .pushState(null, "Title", url) // todo: set the title?
    setTimeout(1.millisecond) {
      trigger()
    }
  }

  def urlStream: StrictSignal[Url] = currentUrl.signal

}

object Router {

  final val router: Router = new Router()

  type Url = String

}
