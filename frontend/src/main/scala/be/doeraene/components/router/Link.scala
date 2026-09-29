package be.doeraene.components.router

import be.doeraene.webcomponents.ui5.Link as UI5Link
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import urldsl.language.{PathSegment, PathSegmentWithQueryParams, QueryParameters}

object Link {

  def apply(
      to: PathSegment[Unit, ?]
  )(text: String, modifiers: Modifier[ReactiveHtmlElement[UI5Link.Ref]]*): HtmlElement =
    UI5Link(
      List[Modifier[HtmlElement]](onClick --> (_ => Router.router.moveTo("/" + to.createPath())), text) ++
        modifiers*
    )

  def apply[Q](to: PathSegmentWithQueryParams[Unit, ?, Q, ?], q: Q)(
      text: String,
      modifiers: Modifier[HtmlElement]*
  ): HtmlElement =
    span(
      List[Modifier[HtmlElement]](
        text,
        onClick --> (_ => Router.router.moveTo("/" + to.createUrlString((), q)))
      ) ++ modifiers*
    )

  def apply[Q](to: PathSegment[Unit, ?], withParams: QueryParameters[Q, ?], q: Q)(
      text: String
  ): HtmlElement =
    apply(to ? withParams, q)(text)

}
