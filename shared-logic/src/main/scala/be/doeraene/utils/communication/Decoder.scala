package be.doeraene.utils.communication

import io.circe.parser.{decode => circeDecode}

trait Decoder[In, Out]:
  def decode(in: In): Either[Throwable, Out]

object Decoder:
  type JsonDecoder[Out] = Decoder[Translator.Json, Out]

  given fromCircle[In](using circeDecoder: io.circe.Decoder[In]): JsonDecoder[In] with
    def decode(json: Translator.Json): Either[Throwable, In] = circeDecode[In](json.asString)
