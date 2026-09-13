package be.doeraene.components.gamecomponents

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.{linkModifiers, Constants}
import urldsl.language.dummyErrorImpl.*
import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.models.GameHistory as GameHistoryModel
import be.doeraene.components.router.Link
import be.doeraene.components.router.Router.router
import org.scalajs.dom.html
import org.scalajs.dom
import org.scalajs.dom.FormData
import org.scalajs.dom.Fetch.fetch
import be.doeraene.communication.makeCall.postFormData
import be.doeraene.frontendutils.{ChoseFileButton, PrimaryButton}
import io.circe.Encoder
import be.doeraene.webcomponents.ui5.*

import scala.concurrent.ExecutionContext.Implicits.global

object AILoadGameView {

  def here = againstAI / "load-game"

  val moveToTheGame = Observer[(GameHistoryModel, Option[Team])] {
    (gameHistory: GameHistoryModel, maybeTeam: Option[Team]) =>
      router.moveTo(
        "/" ++ playAIGame
          .createUrlString(
            (),
            (
              Some((gameHistory, maybeTeam)),
              gameHistory.gameType,
              gameHistory.withInitialSpecialRule
            )
          )
      )
  }

  def apply() = {
    def formData(colour: Option[Team], file: dom.File): FormData = {
      val fd = new FormData

      fd.append("colour", colour.fold("random")(_.prettyPrint))
      fd.append("mad-game.mad", file)

      fd
    }

    val submitBus: EventBus[Unit] = new EventBus

    val chosenColour: Var[Option[Team]]    = Var(Option.empty)
    val goToAdvancedSettings: Var[Boolean] = Var(false)

    type ColourSelectValue = "random" | "TeamBlue" | "TeamRed"

    extension (colour: ColourSelectValue)
      def toMaybeTeam: Option[Team] = colour match {
        case "random"   => Option.empty[Team]
        case "TeamRed"  => Some(Team.Red)
        case "TeamBlue" => Some(Team.Blue)
      }

    val maybeChosenFileNameVar: Var[Option[dom.File]] = Var(Option.empty)

    val submitEvents = submitBus.events.sample(chosenColour.signal, maybeChosenFileNameVar.signal).collect {
      case (colour, Some(file)) => formData(colour, file)
    }

    val responses: EventStream[(GameHistoryModel, Option[Team], Boolean)] = submitEvents
      .flatMapSwitch(data => EventStream.fromFuture(postFormData[GameHistoryModel]("api/load-game-ai", data)))
      .withCurrentValueOf(chosenColour.signal)
      .withCurrentValueOf(goToAdvancedSettings.signal)

    val advancedSettingsResponses: EventStream[(GameHistoryModel, Option[Team])] =
      responses.collect { case (gameHistory, maybeTeam, true) => (gameHistory, maybeTeam) }

    val noAdvancedSettingsResponses: EventStream[(GameHistoryModel, Option[Team])] =
      responses.collect { case (gameHistory, maybeTeam, false) => (gameHistory, maybeTeam) }

    div(
      className := "AILoadGameView",
      h1("Challenge Blue Madness!"),
      p("Upload a saved game."),
      child <-- advancedSettingsResponses
        .mapTo(emptyNode)
        .startWith(
          form(
            inContext(el => onSubmit.preventDefault.mapTo(()) --> submitBus.writer),
            noAdvancedSettingsResponses --> moveToTheGame,
            fieldSet(
              display    := "flex",
              alignItems := "center",
              label("Select a saved game", paddingRight := "10px"),
              ChoseFileButton(
                "Choose Mad file",
                fileSelectedObserver = maybeChosenFileNameVar.writer,
                mods = List(nameAttr := "mad-game.mad", accept := ".mad")
              )
            ),
            fieldSet(
              display    := "flex",
              alignItems := "center",
              label("Select your colour", paddingRight := "10px"), {
                val valueAndSelected: Option[Team] => Mod[HtmlElement] = (value: Option[Team]) =>
                  List(
                    Select.option.value     := value.fold("random")(_.prettyPrint),
                    Select.option.selected <-- chosenColour.signal.map(_ == value)
                  )
                Select(
                  _.option("At random", valueAndSelected(None)),
                  _.option("Red", valueAndSelected(Some(Team.Red))),
                  _.option("Blue", valueAndSelected(Some(Team.Blue))),
                  _.events.onChange.map(_.detail.selectedOption.value.toOption).map {
                    case Some("random")   => "random".toMaybeTeam
                    case Some("TeamRed")  => "TeamRed".toMaybeTeam
                    case Some("TeamBlue") => "TeamBlue".toMaybeTeam
                    case _                => throw new RuntimeException("Should not happen")
                  } --> chosenColour
                )
              }
            ),
            div(
              display        := "flex",
              justifyContent := "end",
              input(tpe := "submit", value := "Load game", hidden := true),
              PrimaryButton(
                Val("Load game"),
                Val(false),
                Observer(_.parentElement.querySelector("input[type=submit]").asInstanceOf[html.Input].click())
              )
            )
          )
        )
    )

  }

}
