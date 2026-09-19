package be.doeraene.models

import be.doeraene.mad.game.Team
import be.doeraene.models.WithTime.time.Time

final case class PlayersThinkingTimeInfo(
    redPlayerTotal: Time,
    bluePlayerTotal: Time,
    lastUpdate: Time
):
  def totalForTeam(team: Team): Time = if team == Team.Red then redPlayerTotal else bluePlayerTotal

object PlayersThinkingTimeInfo:
  def initial: PlayersThinkingTimeInfo = PlayersThinkingTimeInfo(Time.zero, Time.zero, Time.zero)
