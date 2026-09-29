package be.doeraene.components.home

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.{downloadRulesComponent, moveToPath}
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.{IconName, WrappingType}
import com.raquo.laminar.api.L.*

object Home:

  def apply(username: String): HtmlElement =
    div(
      className := "Home",
      Title.h1(_.wrappingType := WrappingType.Normal, s"Welcome to mad, $username!"),
      div(
        marginBottom.px := 30,
        marginTop.px    := 30,
        PrimaryButton(
          Val("Play Against the Computer"),
          Val(false),
          moveToPath(againstAI),
          maybeIcon = Some(IconName.laptop)
        )
      ),
      downloadRulesComponent
    )
