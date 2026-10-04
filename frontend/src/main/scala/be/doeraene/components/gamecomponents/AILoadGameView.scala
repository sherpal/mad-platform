package be.doeraene.components.gamecomponents

import be.doeraene.components.DisplayGameState
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.router.Router.router
import be.doeraene.facades.jszip.JSZip
import be.doeraene.frontendutils.{ChoseFileButton, PrimaryButton}
import be.doeraene.mad.game.*
import be.doeraene.mad.game.GameBoundaries.GameType
import be.doeraene.models.AIGameOption.Difficulty
import be.doeraene.models.{AIGameOption, GameHistory as GameHistoryModel}
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.ButtonDesign
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.html
import urldsl.errors.DummyError
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*

import scala.concurrent.ExecutionContext.Implicits.global

object AILoadGameView {

  def here: PathSegment[Unit, DummyError] = againstAI / "load-game"

  private val moveToTheGame = Observer[(GameHistoryModel, AIGameOption)] {
    (gameHistory: GameHistoryModel, options: AIGameOption) =>
      router.moveTo(
        "/" ++ playAIGame
          .createUrlString(
            (),
            (
              Some(gameHistory),
              gameHistory.gameType,
              options
            )
          )
      )
  }

  def apply(): HtmlElement = {
    val submitBus: EventBus[Unit] = new EventBus

    val chosenColour: Var[Option[Team]] = Var(Option.empty)
    val difficultyLevelVar              = Var(Difficulty.of(0))

    case class LoadedData(history: GameHistoryModel, options: AIGameOption)

    val loadedDataVar = Var(Option.empty[LoadedData])

    type ColourSelectValue = "random" | "TeamBlue" | "TeamRed"

    extension (colour: ColourSelectValue)
      private def toMaybeTeam: Option[Team] = colour match {
        case "random"   => Option.empty[Team]
        case "TeamRed"  => Some(Team.Red)
        case "TeamBlue" => Some(Team.Blue)
      }

    val maybeChosenFileNameVar: Var[Option[dom.File]] = Var(Option.empty)

    val submitEvents =
      submitBus.events.sample(chosenColour.signal, difficultyLevelVar.signal, loadedDataVar.signal).collect {
        case (colour, difficulty, Some(data)) =>
          (
            data.history,
            AIGameOption(colour, difficulty, data.options.withInitialSpecialRule)
          )
      }

    val loadedDataChanges = maybeChosenFileNameVar.signal.changes
      .collect { case Some(file) => file }
      .flatMapSwitch(file =>
        EventStream
          .fromFuture(
            JSZip
              .load(file)
              .flatMap(be.doeraene.components.extractGameHistory)
              .map[Either[Throwable, (history: GameHistoryModel, options: AIGameOption)]](Right.apply)
              .recover { case throwable: Throwable =>
                Left(throwable)
              }
          )
      )
    val loadDataSuccesses = loadedDataChanges.collect { case Right(data) => data }

    val loadErrorsEvents: EventStream[Throwable] = loadedDataChanges.collect { case Left(throwable) => throwable }
    val closeErrorDialogBus                      = new EventBus[Unit]

    def chooseFileFieldSet = fieldSet(
      className := "settings-row",
      Label("Select a saved game", paddingRight := "10px"),
      ChoseFileButton(
        "Choose Mad file",
        fileSelectedObserver = maybeChosenFileNameVar.writer,
        mods = List(nameAttr := "mad-game.mad", accept := ".mad")
      )
    )

    div(
      className := "AILoadGameView",
      Title.h1("Challenge Blue Madness!"),
      Text("Upload a saved game."),
      loadedDataChanges.map(_.toOption.map(data => LoadedData(data.history, data.options))) --> loadedDataVar.writer,
      loadDataSuccesses.map(_.options.difficultyLevel) --> difficultyLevelVar.writer,
      loadDataSuccesses.map(_.options.maybePlayerTeam) --> chosenColour.writer,
      form(
        onSubmit.preventDefault.mapTo(()) --> submitBus.writer,
        submitEvents --> moveToTheGame,
        chooseFileFieldSet,
        children <-- loadedDataVar.signal.map {
          case None => // need to ask to upload file
            Seq(Text("Choose a .mad file on your device."))
          case Some(data) => // need to finish the pipe
            Vector(
              fieldSet(
                Label("Difficulty"),
                DifficultyLevelSelector(
                  difficultyLevelVar = difficultyLevelVar,
                  chosenGameTypeSignal = loadedDataVar.signal.map(
                    _.map(_.history.initialGameState.gameType).getOrElse(GameType.existingGameTypes.head)
                  )
                )
              ),
              fieldSet(
                className := "settings-row",
                Label("Select your colour", paddingRight := "10px"), {
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
                      case _                => throw RuntimeException("Should not happen")
                    } --> chosenColour
                  )
                }
              ),
              div(
                display.flex,
                justifyContent.end,
                input(tpe := "submit", value := "Load game", hidden := true),
                PrimaryButton(
                  Val("Load game"),
                  Val(false),
                  Observer(_.parentElement.querySelector("input[type=submit]").asInstanceOf[html.Input].click())
                )
              )
            )
        }
      ),
      child.maybe <-- loadedDataVar.signal.map(_.map { data =>
        div(
          Title.h2("Loaded game state"),
          DisplayGameState(
            data.history.initialGameState.gameBoundaries,
            Val(data.history.currentGameState),
            Val(None),
            data.options.maybePlayerTeam.getOrElse(Team.Red),
            Observer.empty,
            Observer.empty,
            Val(None),
            Observer.empty
          )
        )
      }),
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
