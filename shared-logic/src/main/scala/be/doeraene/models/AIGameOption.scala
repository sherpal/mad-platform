package be.doeraene.models

import be.doeraene.mad.game.Team
import io.circe.Codec
import be.doeraene.utils.communication.MadTranslators.given

final case class AIGameOption(
    maybePlayerTeam: Option[Team],
    difficultyLevel: Int,
    withInitialSpecialRule: Boolean
) derives Codec {

  def turnAhead: Int = difficultyLevel match {
    case 0 => 0
    case 1 => 1
    case 2 => 3
    case 3 => 4
    case 4 => 4
    case _ => 5
  }

}

object AIGameOption {
  def default: AIGameOption = AIGameOption(
    maybePlayerTeam = None,
    difficultyLevel = 3,
    withInitialSpecialRule = false
  )
}
