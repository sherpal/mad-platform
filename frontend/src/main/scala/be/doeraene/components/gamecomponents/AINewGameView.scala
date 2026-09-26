package be.doeraene.components.gamecomponents

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.mad.game.*
import be.doeraene.workers.NeuralModels
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.models.{AIGameOption, GameHistory as GameHistoryModel}
import be.doeraene.services.LocalStorageService
import be.doeraene.utils.communication.MadTranslators
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom.HTMLDivElement
import urldsl.errors.DummyError
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*
import be.doeraene.components.router.Router.router
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.mad.game.GameBoundaries.GameType
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.IconName
import org.scalajs.dom

object AINewGameView {

  def here: PathSegment[Unit, DummyError] = againstAI / "new-game"

  def apply(): ReactiveHtmlElement[HTMLDivElement] = {
    val storage       = LocalStorageService()
    val gameConfigKey = storage.key[GameConfig]("game-config")

    val chosenGameType: Var[GameBoundaries.GameType] = Var(GameBoundaries._5by5)
    val unrestrictedMoveOnFirstTurnVar: Var[Boolean] = Var(true)

    val startGameBus: EventBus[Unit] = new EventBus

    val gameModeWriter = chosenGameType.writer

    val selectGameMode = {
      val value: GameType => Mod[HtmlElement] = gameType =>
        List(
          Select.option.value     := gameType.value,
          Select.option.selected <-- chosenGameType.signal.map(_.value == gameType.value)
        )
      Select(
        marginLeft := "20px",
        _.option(
          "Board 6x4",
          value(GameBoundaries._6by4)
        ),
        _.option(
          "Board 5x5",
          value(GameBoundaries._5by5)
        ),
        _.option(
          "Board 4x6",
          value(GameBoundaries._4by6)
        ),
        _.option(
          "Aztec Diamond",
          value(GameBoundaries.aztecDiamond)
        ),
        _.events.onChange
          .map(_.detail.selectedOption.value.toOption.get)
          .map(GameBoundaries.GameType.fromString(_).toTry.get) --> gameModeWriter,
        paddingLeft := "20px"
      )
    }

    val switchInitialSpecialRule = span(
      paddingLeft := "20px",
      Switch(
        _.checked <-- unrestrictedMoveOnFirstTurnVar.signal,
        _.events.onChange.map(_.target.checked) --> unrestrictedMoveOnFirstTurnVar.writer
      )
    )

    val turnAhead = Var(4)
    /* Defaults to the network because it is simply the stronger player now: searching over it beats the
     * minimax at depth 4, which is the minimax's own best setting. The old engine stays selectable -
     * it is what every previous game was played against, and it is the only one that works if the model
     * has not been fetched. */
    val useNeural   = Var(true)
    val simulations = Var(800)

    val difficultyLevelVar = Var(3)

    def difficultyLevelSelector =
      p(
        className := "settings-row",
        "Difficulty level: ",
        Select(
          marginLeft := "20px",
          _.option(
            "1 (easy)",
            Select.option.value     := "1",
            Select.option.selected <-- difficultyLevelVar.signal.map(_ == 1)
          ),
          _.option(
            "2 (medium)",
            Select.option.value     := "2",
            Select.option.selected <-- difficultyLevelVar.signal.map(_ == 2)
          ),
          _.option(
            "3 (hard)",
            Select.option.value     := "3",
            Select.option.selected <-- difficultyLevelVar.signal.map(_ == 3)
          ),
          child.maybe <-- chosenGameType.signal.map(gameType =>
            Option.when(NeuralModels.isTrained(gameType))(
              Select.option(
                "4 (very hard)",
                Select.option.value     := "4",
                Select.option.selected <-- difficultyLevelVar.signal.map(_ == 4)
              )
            )
          ),
          _.events.onChange
            .map(_.detail.selectedOption.value.toOption.get.toInt) --> difficultyLevelVar.writer,
          onMountCallback(_ => difficultyLevelVar.set(3)),
          /* Level 4 is the neural engine, and only boards with a trained network can offer it. Dropping
           * the selection to 3 when the player picks a board without one is what stops the engine being
           * chosen and then failing; NeuralModels is the same list the worker loads its files from. */
          chosenGameType.signal.changes.map(gameType =>
            if NeuralModels.isTrained(gameType) then 4 else 3
          ) --> difficultyLevelVar
            .updater[Int](_.min(_))
        )
      )

    div(
      className := "AINewGameView",
      Title.h1("Challenge Blue Madness!"),
      p(
        className := "settings-row",
        "Select your game mode: ",
        selectGameMode
      ),
      difficultyLevelSelector,
      p(className := "settings-row", "Unrestricted moves on the first turn:", switchInitialSpecialRule),
      p(
        startGameBus.events.sample(
          chosenGameType.signal,
          difficultyLevelVar.signal,
          unrestrictedMoveOnFirstTurnVar.signal.map(!_)
        ) --> { case (gameType: GameBoundaries.GameType, difficulty: Int, specialRule: Boolean) =>
          val gameOption = AIGameOption(Option.empty, difficulty, specialRule)

          try
            storage.store(gameConfigKey, GameConfig(gameType, gameOption))
          catch {
            case t: Throwable =>
              dom.console.error("Failed to store game configuration, ignoring...", t)
          }

          router.moveTo("/" ++ playAIGame.createUrlString((), (Option.empty[GameHistoryModel], gameType, gameOption)))
        },
        PrimaryButton(
          Val("Start game"),
          Val(false),
          startGameBus.writer.contramap(_ => ()),
          maybeIcon = Some(IconName.`media-play`)
        )
      ),
      onMountCallback { _ =>
        val options = storage.retrieve(gameConfigKey).getOrElse(GameConfig.default)
        chosenGameType.set(options.gameType)
        unrestrictedMoveOnFirstTurnVar.set(!options.options.withInitialSpecialRule)
        difficultyLevelVar.set(options.options.difficultyLevel)
      }
    )
  }

  import MadTranslators.given
  private case class GameConfig(gameType: GameType, options: AIGameOption) derives io.circe.Codec
  private object GameConfig {
    def default: GameConfig = GameConfig(GameBoundaries._6by4, AIGameOption(Option.empty, 3, false))
  }

}
