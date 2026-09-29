package be.doeraene.frontendutils

import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName}
import com.raquo.laminar.api.L.*
import org.scalajs.dom

object PrimaryButton {

  def apply(
      label: Signal[String],
      disabled: Observable[Boolean],
      clickObserver: Observer[dom.html.Element],
      raised: Boolean = true,
      maybeIcon: Option[IconName] = None
  ): HtmlElement = Button(
    className   := "PrimaryButton",
    child.text <-- label,
    _.disabled <-- disabled,
    _.design    := (if raised then ButtonDesign.Emphasized else ButtonDesign.Transparent),
    inContext(el => onClick.mapTo(el.ref) --> clickObserver),
    maybeIcon.fold(emptyMod)(Button.icon := _)
  )

}
