package be.doeraene.frontendutils

import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.*
import org.scalajs.dom
import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.configkeys.ButtonDesign

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
