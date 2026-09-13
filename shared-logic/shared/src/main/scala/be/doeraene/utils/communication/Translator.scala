package be.doeraene.utils.communication

trait Translator[In, Out]:
  def encode(in: In): Out
  def decode(out: Out): Either[Throwable, In]

object Translator:
  opaque type Json = String
  object Json:
    def fromString(jsonString: String): Json = jsonString
    extension (json: Json)
      def asString: String = json
    given jsonEncoder: Encoder[Json, String] with
      def encode(json: Json): String = json.asString
    given jsonDecoder: Decoder[String, Json] with
      def decode(str: String): Either[Throwable, Json] = Right(str)

  type JsonTranslator[T] = Translator[T, Json]

  given translator[In, Out](using encoder: Encoder[In, Out], decoder: Decoder[Out, In]): Translator[In, Out] with
    def encode(in: In): Out = encoder.encode(in)
    def decode(out: Out): Either[Throwable, In] = decoder.decode(out)

