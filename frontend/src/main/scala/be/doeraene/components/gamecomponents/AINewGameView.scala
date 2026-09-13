package be.doeraene.components.gamecomponents

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.{linkModifiers, Constants}
import urldsl.language.dummyErrorImpl.*
import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.models.GameHistory as GameHistoryModel
import be.doeraene.mad.game.GameBoundaries
//import be.doeraene.components.material.{Icon, ListItem, Select, Switch}
import be.doeraene.components.router.Link
import be.doeraene.components.router.Router.router
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.*
import be.doeraene.mad.game.GameBoundaries.GameType

object AINewGameView {

  def here = againstAI / "new-game"

  def apply() = {

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

    div(
      Title.h1("Challenge Blue Madness!"),
      p(
        display    := "flex",
        alignItems := "center",
        "Select your game mode: ",
        selectGameMode
      ),
      p(display := "flex", alignItems := "center", "Unrestricted moves on the first turn:", switchInitialSpecialRule),
      p(
        startGameBus.events.sample(chosenGameType.signal, unrestrictedMoveOnFirstTurnVar.signal.map(!_)) --> {
          case (gameType: GameBoundaries.GameType, specialRule: Boolean) =>
            router.moveTo("/" ++ playAIGame.createUrlString((), (Option.empty, gameType, specialRule)))
        },
        PrimaryButton(
          Val("Start game"),
          Val(false),
          startGameBus.writer.contramap(_ => ()),
          maybeIcon = Some(IconName.`media-play`)
        )
      ),
      onMountCallback(_ => chosenGameType.set(GameBoundaries._6by4))
    )
  }

}
