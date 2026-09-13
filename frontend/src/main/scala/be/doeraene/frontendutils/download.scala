package be.doeraene.frontendutils

import org.scalajs.dom

import scala.scalajs.js.URIUtils.encodeURIComponent


object download:

  def apply(filename: String, contents: String): Unit = {
    val a = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    a.setAttribute("href", "data:text/plain;charset=utf-8," ++ encodeURIComponent(contents))
    a.setAttribute("download", filename)
    a.style.display = "none"
    dom.document.body.appendChild(a)
    a.click()
    dom.document.body.removeChild(a)
  }

end download
