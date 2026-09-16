package be.doeraene.facades.jszip

import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

type TypeFromOutput[T] <: String = T match {
  case Uint8Array => "uint8array"
  case String     => "string"
  case dom.Blob   => "blob"
}

class GenerateOptions[T] private (val `type`: TypeFromOutput[T]) extends js.Object

object GenerateOptions {
  def apply[T](using value: ValueOf[TypeFromOutput[T]]): GenerateOptions[T] = new GenerateOptions(value.value)
}

@js.native @JSImport("jszip", JSImport.Default)
final class JSZip extends js.Object {
  def file(path: String, bytes: Uint8Array): this.type = js.native

  def file(path: String, content: String): this.type = js.native

  def generateAsync[T](options: GenerateOptions[T]): js.Promise[T] = js.native

  def loadAsync(bytes: Uint8Array): js.Promise[Files] = js.native

  def loadAsync(buff: ArrayBuffer): js.Promise[Files] = js.native
}

object JSZip {
  extension (jsZip: JSZip) {
    def generate[T](using ValueOf[TypeFromOutput[T]]): Future[T] =
      jsZip.generateAsync(GenerateOptions[T]).toFuture
  }

  inline def load(bytes: Uint8Array): Future[Files] = JSZip().loadAsync(bytes).toFuture

  inline def load(buff: ArrayBuffer): Future[Files] = JSZip().loadAsync(buff).toFuture

  def load(file: dom.File)(using ExecutionContext): Future[Files] =
    file.arrayBuffer().toFuture.flatMap(load)
}

@js.native
trait FileInfo extends FileLoader {
  val name: String = js.native
  val dir: Boolean = js.native
  val data: String = js.native
}

@js.native
trait FileLoader extends js.Object {
  @JSName("async")
  def asyncJS[T](value: TypeFromOutput[T]): js.Promise[T] = js.native
}

object FileLoader {
  extension (fileLoader: FileLoader) {
    def async[T](using value: ValueOf[TypeFromOutput[T]]): Future[T] = fileLoader.asyncJS(value.value).toFuture

    def text(using ExecutionContext): Future[String] = for {
      blob    <- fileLoader.async[dom.Blob]
      content <- blob.text().toFuture
    } yield content
  }
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
