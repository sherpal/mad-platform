package be.doeraene.models

import java.time.LocalDateTime
import scala.concurrent.duration.*
import be.doeraene.mad.game.Team

final case class PlayersThinkingTimeInfo(
  redPlayerTotal: FiniteDuration,
  bluePlayerTotal: FiniteDuration,
  lastUpdate: LocalDateTime
):
  def totalForTeam(team: Team): FiniteDuration = if team == Team.Red then redPlayerTotal else bluePlayerTotal

object PlayersThinkingTimeInfo:
  def initial(time: LocalDateTime): PlayersThinkingTimeInfo = PlayersThinkingTimeInfo(
    0.second, 0.second, time
  )
