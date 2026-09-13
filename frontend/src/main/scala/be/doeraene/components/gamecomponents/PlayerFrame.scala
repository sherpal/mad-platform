package be.doeraene.components.gamecomponents

import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import be.doeraene.models.PlayerName

import scala.scalajs.js.timers.*
import java.time.*
import scala.concurrent.duration.{span => _, *}
import be.doeraene.webcomponents.ui5.Icon
import be.doeraene.webcomponents.ui5.configkeys.IconName

object PlayerFrame:

  val playerNameFrame = "player-name"

  private def formatDuration(d: FiniteDuration): String =
    val minutes = d.toMinutes
    val seconds = d.toSeconds % 60
    String.format("%02d", minutes) ++ ":" ++ String.format("%02d", seconds)

  def apply(
      name: PlayerName,
      team: Team,
      isPlaying: Signal[Boolean],
      thinkingTime: Signal[FiniteDuration],
      gameHasEnded: Signal[Boolean]
  ): HtmlElement =
    val clockUpdateBus: EventBus[Unit]                 = new EventBus
    val intervalHandle: Var[Option[SetIntervalHandle]] = Var(Option.empty)

    val isActuallyPlaying = isPlaying.combineWith(gameHasEnded.map(!_)).map(_ && _)
    val lastUpdate        = isActuallyPlaying.mapTo(LocalDateTime.now)
    val thinkingTimeAt    = thinkingTime.combineWith(lastUpdate)
    div(
      className := playerNameFrame,
      display   := "flex",
      width     := "100%",
      className := team.prettyPrint,
      span(
        display    := "flex",
        alignItems := "center",
        child <-- isActuallyPlaying.map(
          if _ then Icon(className := team.prettyPrint, _.name := IconName.`media-play`, marginRight := "0.5em")
          else emptyNode
        ),
        name.display
      ),
      justifyContent := "space-between",
      alignItems     := "center",
      span(
        display    := "flex",
        alignItems := "center",
        child.text <-- clockUpdateBus.events
          .sample(thinkingTimeAt)
          .withCurrentValueOf(isActuallyPlaying)
          .map { (duration: FiniteDuration, at: LocalDateTime, playing: Boolean) =>
            val now                = LocalDateTime.now
            val additionalDuration = if playing then at.until(now, temporal.ChronoUnit.SECONDS).seconds else 0.second
            val totalDuration      = duration + additionalDuration
            totalDuration
          }
          .distinct
          .map(formatDuration),
        Icon(marginLeft := "0.5em", className := team.prettyPrint, _.name := IconName.`fob-watch`)
      ),
      onMountCallback { _ =>
        intervalHandle.now().foreach(clearInterval)
        intervalHandle.update(_ => Some(setInterval(200.millis)(clockUpdateBus.writer.onNext(()))))
      },
      onUnmountCallback(_ => intervalHandle.now().foreach(clearInterval))
    )

end PlayerFrame
