package be.doeraene.frontendutils

import be.doeraene.webcomponents.ui5.*
import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.configkeys.ButtonDesign
import be.doeraene.webcomponents.ui5.configkeys.IconName

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
