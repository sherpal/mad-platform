package be.doeraene.components.gamecomponents

import be.doeraene.components.RouteDefinitions.*
import be.doeraene.components.moveToPath
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.models.PlayerName
import be.doeraene.webcomponents.ui5.configkeys.IconName
import com.raquo.laminar.api.L.*

object AIPreGameView:

  val here = againstAI

  def apply(playerName: PlayerName): HtmlElement = {
    val newGame =
      PrimaryButton(Val("New Game"), Val(false), moveToPath(AINewGameView.here), maybeIcon = Some(IconName.flag))

    val loadGame =
      PrimaryButton(Val("Load Game"), Val(false), moveToPath(AILoadGameView.here), maybeIcon = Some(IconName.upload))

    div(
      className := "AIPreGameView",
      h1("Challenge Blue Madness!"),
      h2(newGame),
      h2(loadGame)
    )
  }

end AIPreGameView
