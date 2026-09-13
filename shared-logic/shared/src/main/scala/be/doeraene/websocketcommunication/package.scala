package be.doeraene

import io.circe.Decoder

package object websocketcommunication {
  def valueDecoder[T](t: T): Decoder[T] = Decoder[String].emapTry {
    case s if s == t.toString => scala.util.Success(t)
    case s                    => scala.util.Failure(new RuntimeException(s"Decoded string value $s was not equal to $t"))
  }

  implicit class Widen[T](instance: Decoder[T]):
    def widen[U](using ev: T <:< U): Decoder[U] = instance.map(ev.apply)


}
