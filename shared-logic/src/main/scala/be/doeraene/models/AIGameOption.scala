package be.doeraene.models

import be.doeraene.mad.game.Team
import io.circe.{Codec, Decoder, Encoder}
import be.doeraene.utils.communication.MadTranslators.given

final case class AIGameOption(
    maybePlayerTeam: Option[Team],
    difficultyLevel: AIGameOption.Difficulty,
    withInitialSpecialRule: Boolean
) derives Codec {

  /** Turn ahead for minimax algo */
  def turnAhead: Int = difficultyLevel.turnAhead

  /** Number of simulations for MCTS algo */
  def sims: Int = difficultyLevel.sims

}

object AIGameOption {
  def default: AIGameOption = AIGameOption(
    maybePlayerTeam = None,
    difficultyLevel = 3,
    withInitialSpecialRule = false
  )

  opaque type Difficulty = Int

  object Difficulty {
    inline def of(inline n: Int): Difficulty =
      inline if n >= 0 && n <= 5 then n else compiletime.error(s"expected a difficulty value between 0 and 5, got $n")

    def unsafe(n: Int): Difficulty = n

    enum Type:
      case Random, Minimax, MCTS

    extension (difficulty: Difficulty) {
      def value: Int = difficulty

      def name: String = difficulty match {
        case 0 => "learn the rules"
        case 1 => "easy"
        case 2 => "medium"
        case 3 => "hard"
        case 4 => "very hard"
        case 5 => "insane"
        case _ => "unknown"
      }

      def turnAhead: Int = difficulty match {
        case 0 => 0
        case 1 => 1
        case 2 => 3
        case 3 => 4
        case _ => 4
      }

      def sims: Int = difficulty match {
        case 4 => 800
        case 5 => 4000
        case _ => 800
      }

      def tpe: Type = difficulty match {
        case 0          => Type.Random
        case n if n < 4 => Type.Minimax
        case _          => Type.MCTS
      }

      def min(that: Difficulty): Difficulty = if difficulty < that then difficulty else that
    }

    given Encoder[Difficulty] = Encoder.encodeInt
    given Decoder[Difficulty] = Decoder.decodeInt
  }
}
