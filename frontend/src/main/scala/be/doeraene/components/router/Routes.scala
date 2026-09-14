package be.doeraene.components.router

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.scalajs.dom
import org.scalajs.dom.Element

object Routes {

  def apply(
      routes: List[Route[? <: dom.html.Element, ?]]
  ): Signal[List[HtmlElement]] =
    Router.router.urlStream.map(url => routes.flatMap(_.maybeMakeRenderer(url)).map(_()))

  def apply(routes: Route[? <: dom.html.Element, ?]*): Signal[List[HtmlElement]] = apply(routes.toList)

  def firstOf(routes: List[Route[? <: dom.html.Element, ?]]): Signal[Option[HtmlElement]] =
    Router.router.urlStream.map(url => routes.flatMap(_.maybeMakeRenderer(url)).headOption.map(_()))

  def firstOf(routes: Route[? <: dom.html.Element, ?]*): Signal[Option[HtmlElement]] = firstOf(routes.toList)
}
