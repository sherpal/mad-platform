package be.doeraene.components.gamecomponents

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.router.Router.router
import be.doeraene.facades.jszip.JSZip
import be.doeraene.frontendutils.{ChoseFileButton, PrimaryButton}
import be.doeraene.mad.game.*
import be.doeraene.models.GameHistory as GameHistoryModel
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.ButtonDesign
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.{html, FormData}
import urldsl.errors.DummyError
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*

import scala.concurrent.ExecutionContext.Implicits.global

object AILoadGameView {

  def here: PathSegment[Unit, DummyError] = againstAI / "load-game"

  private val moveToTheGame = Observer[(GameHistoryModel, Option[Team])] {
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

  def apply(): HtmlElement = {
    val submitBus: EventBus[Unit] = new EventBus

    val chosenColour: Var[Option[Team]]    = Var(Option.empty)
    val goToAdvancedSettings: Var[Boolean] = Var(false)

    type ColourSelectValue = "random" | "TeamBlue" | "TeamRed"

    extension (colour: ColourSelectValue)
      private def toMaybeTeam: Option[Team] = colour match {
        case "random"   => Option.empty[Team]
        case "TeamRed"  => Some(Team.Red)
        case "TeamBlue" => Some(Team.Blue)
      }

    val maybeChosenFileNameVar: Var[Option[dom.File]] = Var(Option.empty)

    val submitEvents = submitBus.events.sample(chosenColour.signal, maybeChosenFileNameVar.signal).collect {
      case (colour, Some(file)) => (colour = colour, file = file)
    }

    val responses: EventStream[(Either[Throwable, GameHistoryModel], Option[Team], Boolean)] = submitEvents
      .flatMapSwitch(data =>
        EventStream
          .fromFuture(
            JSZip
              .load(data.file)
              .flatMap(be.doeraene.components.extractGameHistory)
              .map[Either[Throwable, GameHistoryModel]](Right.apply)
              .recover { case throwable: Throwable =>
                Left(throwable)
              }
          )
          .map(gameHistory => (gameHistory, data.colour))
      )
      .withCurrentValueOf(goToAdvancedSettings.signal)

    val advancedSettingsResponses: EventStream[(GameHistoryModel, Option[Team])] =
      responses.collect { case (Right(gameHistory), maybeTeam, true) => (gameHistory, maybeTeam) }

    val noAdvancedSettingsResponses: EventStream[(GameHistoryModel, Option[Team])] =
      responses.collect { case (Right(gameHistory), maybeTeam, false) => (gameHistory, maybeTeam) }

    val loadErrorsEvents: EventStream[Throwable] = responses.map(_._1).collect { case Left(throwable) => throwable }
    val closeErrorDialogBus                      = new EventBus[Unit]

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
        ),
      Dialog.of(
        _.showFromEvents(loadErrorsEvents.mapToUnit),
        _.closeFromEvents(closeErrorDialogBus.events),
        _.headerText := "Error while loading game",
        _ =>
          p(
            "Error while loading game: ",
            child.text <-- loadErrorsEvents.map(throwable => Option(throwable.getMessage).getOrElse("Unknown Error")),
            div {
              val openVar = Var(false)
              Vector[Mod[HtmlElement]](
                child.maybe <-- openVar.signal.invert.map(
                  Option.when(_)(
                    span(
                      cursor.pointer,
                      textDecoration.underline,
                      "Show Details...",
                      onClick.mapTo(true) --> openVar.writer
                    )
                  )
                ),
                child.maybe <-- openVar.signal.map(
                  Option.when(_)(
                    pre(
                      overflowX.auto,
                      width.percent := 100,
                      child.text   <-- loadErrorsEvents.map(be.doeraene.utils.displayThrowable)
                    )
                  )
                )
              )
            }
          ),
        _.slots.footer := Bar.of(
          _.slots.endContent := Button.of(
            _.design := ButtonDesign.Transparent,
            _ => "Close",
            _.events.onClick.mapToUnit --> closeErrorDialogBus.writer
          )
        )
      )
    )

  }

}
