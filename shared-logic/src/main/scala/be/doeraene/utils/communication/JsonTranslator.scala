package be.doeraene.utils.communication

object JsonTranslator:
  def apply[T](using translator: Translator[T, Translator.Json]): Translator[T, Translator.Json] = translator