package be.doeraene.frontendutils

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.scalajs.dom
import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.*

object ChoseFileButton {

  def apply(
      text: String,
      disabledObservable: Observable[Boolean] = Val(false),
      fileSelectedObserver: Observer[Option[dom.File]] = Observer.empty,
      mods: List[Modifier[HtmlElement]] = Nil
  ): HtmlElement =
    FileUploader(
      _.placeholder := text,
      _.events.onChange.map(_.detail.files.headOption) --> fileSelectedObserver
    )

}
