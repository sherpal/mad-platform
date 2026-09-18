package be.doeraene.components.home

import com.raquo.laminar.api.L.*
import be.doeraene.components.router.*
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.{downloadRulesComponent, linkModifiers, moveToPath}
import be.doeraene.webcomponents.ui5.{Link => _, *}
import be.doeraene.frontendutils.PrimaryButton
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.endOfSegments
import be.doeraene.webcomponents.ui5.configkeys.{IconName, WrappingType}

object Home:

  def apply(username: String): HtmlElement = {
    val againstAILink = Link(againstAI / endOfSegments)("here", linkModifiers*)

    def menuRow(text: HtmlElement, icon: HtmlElement, moveTo: PathSegment[Unit, ?]) = UList.item(
      div(
        display    := "flex",
        alignItems := "center",
        icon,
        span(paddingLeft := "10px", text)
      ),
      onClick.mapTo(()) --> moveToPath(moveTo)
    )

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
  }
