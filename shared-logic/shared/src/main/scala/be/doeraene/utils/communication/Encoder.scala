package be.doeraene.utils.communication

trait Encoder[In, Out]:
  def encode(in: In): Out

object Encoder:
  type JsonEncoder[In] = Encoder[In, Translator.Json]

  given fromCirce[In](using circeEncoder: io.circe.Encoder[In]): JsonEncoder[In] with
    def encode(in: In): Translator.Json = Translator.Json.fromString(circeEncoder(in).noSpaces)
end Encoder
