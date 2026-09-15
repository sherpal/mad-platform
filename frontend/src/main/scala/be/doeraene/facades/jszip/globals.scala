package be.doeraene.facades.jszip

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

type TypeFromOutput[T] <: String = T match {
  case Uint8Array           => "uint8array"
  case String               => "string"
  case org.scalajs.dom.Blob => "blob"
}

trait GenerateOptions[T] extends js.Object {
  val `type`: TypeFromOutput[T]
}

object GenerateOptions {
  def apply[T](using value: ValueOf[TypeFromOutput[T]]): GenerateOptions[T] = new GenerateOptions[T] {
    val `type`: TypeFromOutput[T] = value.value
  }
}

@js.native @JSImport("jszip", JSImport.Default)
final class JSZip extends js.Object {
  @JSName("file")
  def fileJS(path: String, bytes: Uint8Array): this.type = js.native

  @JSName("file")
  def fileJSString(path: String, content: String): this.type = js.native

  def generateAsync[T](options: GenerateOptions[T]): js.Promise[T] = js.native

  def loadAsync(bytes: Uint8Array): js.Promise[Files] = js.native
}

object JSZip {
  extension (jsZip: JSZip) {
    def generate[T](options: GenerateOptions[T]): Future[T] =
      jsZip.generateAsync(options).toFuture

    def load(bytes: Uint8Array): Future[Files] = jsZip.loadAsync(bytes).toFuture
  }
}

trait FileInfo extends js.Object {
  val name: String
  val dir: Boolean
  val data: String
}

@js.native
trait FileLoader extends js.Object {
  def async[T](value: TypeFromOutput[T]): js.Promise[T] = js.native
}

@js.native
trait Files extends js.Object {
  val files: js.Dictionary[FileInfo] = js.native

  def file(path: String): FileLoader = js.native
}

object Files {
  extension (files: Files) {
    def allFileNames: Set[String] = files.files.toMap.keySet
  }
}
