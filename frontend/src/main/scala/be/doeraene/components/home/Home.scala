package be.doeraene.components.home

import com.raquo.laminar.api.L.*
import be.doeraene.components.router.*
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.{downloadRulesComponent, linkModifiers, moveToPath}
import be.doeraene.webcomponents.ui5.{Link => _, *}
import be.doeraene.frontendutils.PrimaryButton
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.endOfSegments
import be.doeraene.webcomponents.ui5.configkeys.IconName

object Home:

  def apply(username: String): HtmlElement = {
    val againstAILink    = Link(againstAI / endOfSegments)("here", linkModifiers*)
    val againstHumanLink = Link(againstHuman)("here", linkModifiers*)

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
      h1(s"Welcome to mad, $username!"),
      UList(
        menuRow(span("Play Against the Computer"), Icon(_.name := IconName.laptop), againstAI),
        // menuRow(span("Play Against a Human"), Icon.personOutline, againstHuman),
        marginBottom := "30px"
      ),
      downloadRulesComponent
    )
  }
