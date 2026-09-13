package be.doeraene.frontendutils

import com.raquo.laminar.api.L._
import be.doeraene.facades.rawdom.Audio
import org.scalajs.dom.Event
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.timers._

import scala.concurrent.duration._

object notification:

  private val audio = new Audio("/assets/sounds/notification.wav")

  /** 
   * Plays a small notification sound whenever the `notificationEvents` stream emits, and the window
   * does *not* have the focus.
   * While the window stays blurred after the notification, if the `maybeTitleWhenNotified` was specificed, it will
   * change every second between the default title and this one.
   */
  def apply(notificationEvents: EventStream[Unit], maybeTitleWhenNotified: Option[String]): HtmlElement = 
    val tabIsFocused: Var[Boolean] = Var(true) // not necessarily true
    val defaultTitle: Var[Option[String]] = Var(Option.empty)
    val wasNotified: Var[Boolean] = Var(false)

    val windowGetsFocused: js.Function1[Event, Unit] = (_: Event) => {
      tabIsFocused.update(_ => true)
      defaultTitle.now().foreach { title => 
        dom.document.title = title
      }
      wasNotified.update(_ => false)
    }
    val windowLosesFocus: js.Function1[Event, Unit] = (_: Event) => tabIsFocused.update(_ => false)

    val titleShouldChangeBus: EventBus[Unit] = new EventBus
    val titleChangeIntervalHandle: Var[Option[SetIntervalHandle]] = Var(Option.empty)


    div(
      onMountCallback { _ =>
        dom.window.addEventListener("focus", windowGetsFocused)
        dom.window.addEventListener("blur", windowLosesFocus)

        maybeTitleWhenNotified.foreach { title =>
          defaultTitle.update(_ => Some(dom.document.title))
          titleChangeIntervalHandle.update(_ => Some(setInterval(1.second)(titleShouldChangeBus.writer.onNext(()))))
        }
      },
      onUnmountCallback { _ =>
        dom.window.removeEventListener("focus", windowGetsFocused)
        dom.window.removeEventListener("blur", windowLosesFocus)
      },
      notificationEvents.sample(tabIsFocused).filter(!_) --> { 
        (_: Boolean) => 
          audio.play()
          wasNotified.update(_ => true)
      },
      titleShouldChangeBus.events
        .sample(wasNotified)
        .filter(identity)
        .sample(defaultTitle)
        .collect { case Some(title) => title }
        .map(title => (title, maybeTitleWhenNotified))
        .collect { case (title, Some(titleWhenNotified)) => title -> titleWhenNotified } --> { 
          (title: String, titleWhenNotified: String) => 
          if dom.document.title == title then dom.document.title = titleWhenNotified
          else dom.document.title = title
        
        }
    )

  def apply(notificationEvents: EventStream[Unit], titleWhenNotified: String): HtmlElement =
    apply(notificationEvents, Some(titleWhenNotified))

end notification
