package be.doeraene.frontendutils

import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName}
import com.raquo.laminar.api.L.*

object SecondaryButton {

  def apply(
      label: Signal[String],
      disabled: Observable[Boolean],
      clickObserver: Observer[Unit],
      raised: Boolean = true,
      maybeIcon: Option[IconName] = None
  ): HtmlElement = Button(
    className   := "SecondaryButton",
    child.text <-- label,
    _.design    := ButtonDesign.Transparent,
    _.disabled <-- disabled,
    onClick.mapTo(()) --> clickObserver,
    maybeIcon.map(icon => Button.icon := icon).getOrElse(emptyMod)
  )

}
