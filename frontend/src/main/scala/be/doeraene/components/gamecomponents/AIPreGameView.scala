package be.doeraene.components.gamecomponents

import com.raquo.laminar.api.L.*
import be.doeraene.components.router.Link
import be.doeraene.components.router.Router.router
import be.doeraene.models.PlayerName
import be.doeraene.components.RouteDefinitions.playAIGame
import be.doeraene.mad.game.*
import be.doeraene.utils.communication.MadTranslators.given
import be.doeraene.models.GameHistory as GameHistoryModel
import be.doeraene.components.router.Router.router
import io.circe.generic.auto.*
import org.scalajs.dom.html
import org.scalajs.dom
import org.scalajs.dom.FormData
import org.scalajs.dom.Fetch.fetch
import be.doeraene.communication.makeCall.postFormData
import be.doeraene.components.{linkModifiers, moveToPath}
import io.circe.Encoder
import be.doeraene.components.RouteDefinitions.*
import be.doeraene.frontendutils.PrimaryButton
import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.*

object AIPreGameView:

  val here = againstAI

  def apply(playerName: PlayerName): HtmlElement = {
    val newGame =
      PrimaryButton(Val("New Game"), Val(false), moveToPath(AINewGameView.here), maybeIcon = Some(IconName.flag))

    val loadGame =
      PrimaryButton(Val("Load Game"), Val(false), moveToPath(AILoadGameView.here), maybeIcon = Some(IconName.upload))

    div(
      h1("Challenge Blue Madness!"),
      h2(newGame),
      h2(loadGame)
    )
  }

end AIPreGameView
