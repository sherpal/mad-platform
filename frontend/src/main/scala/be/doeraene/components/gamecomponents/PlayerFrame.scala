package be.doeraene.components.gamecomponents

import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import be.doeraene.models.{PlayerName, WithTime}

import scala.scalajs.js.timers.*
import be.doeraene.webcomponents.ui5.Icon
import be.doeraene.webcomponents.ui5.configkeys.IconName

object PlayerFrame:

  private val playerNameFrame = "player-name"

  def apply(
      name: PlayerName,
      team: Team,
      isPlaying: Signal[Boolean],
      thinkingTime: Signal[WithTime.time.Time],
      gameHasEnded: Signal[Boolean]
  ): HtmlElement =
    val clockUpdateBus: EventBus[Unit]                 = new EventBus
    val intervalHandle: Var[Option[SetIntervalHandle]] = Var(Option.empty)

    val isActuallyPlaying = isPlaying.combineWith(gameHasEnded.map(!_)).map(_ && _)
    val lastUpdate        = isActuallyPlaying.mapTo(WithTime.time.Time.now())
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
          .map { (duration: WithTime.time.Time, at: WithTime.time.Time, playing: Boolean) =>
            val now                = WithTime.time.Time.now()
            val additionalDuration = if playing then at.until(now) else WithTime.time.Time.zero
            val totalDuration      = duration + additionalDuration
            totalDuration
          }
          .distinct
          .map(_.format),
        Icon(marginLeft := "0.5em", className := team.prettyPrint, _.name := IconName.`fob-watch`)
      ),
      onMountCallback { _ =>
        intervalHandle.now().foreach(clearInterval)
        intervalHandle.update(_ => Some(setInterval(200)(clockUpdateBus.writer.onNext(()))))
      },
      onUnmountCallback(_ => intervalHandle.now().foreach(clearInterval))
    )

end PlayerFrame
