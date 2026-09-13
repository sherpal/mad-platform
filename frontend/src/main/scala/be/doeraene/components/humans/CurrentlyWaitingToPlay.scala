package be.doeraene.components.humans

import be.doeraene.frontendutils.{PrimaryButton, SecondaryButton}
import com.raquo.laminar.api.L.*
import be.doeraene.websocketcommunication.ClientToServerWaitingRoom.{InvitationLinkPlease, PlayAgainst}
import be.doeraene.websocketcommunication.ServerToClientWaitingRoom.InvitationLink
import org.scalajs.dom

import scala.scalajs.js.URIUtils
import scala.scalajs.js
import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.*

object CurrentlyWaitingToPlay:

  private def startWaitingButton(startWaitingObserver: Observer[Any]) =
    PrimaryButton(Val("Create playing table"), Val(false), startWaitingObserver, maybeIcon = Some(IconName.group))

  def apply(
      username: String,
      waitingPlayers: EventStream[List[String]],
      startWaitingObserver: Observer[Any],
      stopWaitingObserver: Observer[Any],
      playingAgainstObserver: Observer[PlayAgainst],
      invitationLinkEventString: EventStream[InvitationLink],
      invitationLinkObserver: Observer[InvitationLinkPlease]
  ): HtmlElement = div(
    h2("Find an opponent"),
    child <-- waitingPlayers.map {
      case Nil =>
        div(
          "Currently, no one is waiting to play. Click on the button below to wait for an opponent!",
          br(),
          startWaitingButton(startWaitingObserver)
        )
      case players if players contains username =>
        div(
          div(
            display    := "flex",
            alignItems := "center",
            "You are currently waiting for an opponent...",
            SecondaryButton(Val("Cancel"), Val(false), stopWaitingObserver)
          ),
          div(
            className := "invite-panel",
            h3("Invite someone to play!"),
            div(
              "Enter a name for your opponent: ",
              input(
                tpe := "text",
                onInput.mapToValue.map(InvitationLinkPlease(_)) --> invitationLinkObserver
              ),
              child <-- invitationLinkEventString.map(_.link).map { link =>
                div(
                  display    := "flex",
                  alignItems := "center",
                  span("Invite your contact by sending them the invite address:", paddingRight := "10px"),
                  PrimaryButton(
                    Val("Copy Invite"),
                    Val(false),
                    Observer { _ =>
                      val element = dom.document.querySelector(".invitation-content")
                      dom.console.log(element)
                      element.asInstanceOf[js.Dynamic].select()
                      dom.document.execCommand("copy")
                    },
                    maybeIcon = Some(IconName.copy)
                  ),
                  input(
                    className := "invitation-content",
                    top       := "-3000px",
                    left      := "-3000px",
                    position  := "absolute",
                    value := dom.window.location.origin.toString ++ "/" ++
                      link.takeWhile(_ != '=') ++ "=" ++
                      URIUtils.encodeURIComponent(link.dropWhile(_ != '=').tail)
                  )
                )
              }
            )
          )
        )
      case players =>
        div(
          p("The following players are waiting. Click on their name to play with them!"),
          div(
            maxWidth := "300px",
            UList(
              players.map(name => UList.item(name, onClick.mapTo(PlayAgainst(name)) --> playingAgainstObserver))
            )
          ),
          p(
            display    := "flex",
            alignItems := "center",
            span("Otherwise, create a new table:", paddingRight := "10px"),
            startWaitingButton(startWaitingObserver)
          )
        )
    }
  )
