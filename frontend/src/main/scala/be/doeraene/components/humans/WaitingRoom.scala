package be.doeraene.components.humans

import com.raquo.laminar.api.L._
import be.doeraene.communication.ProgramsApi._
import be.doeraene.websockets.JsonWebSocket
import be.doeraene.websocketcommunication._
import be.doeraene.globals._
import be.doeraene.components.router.Router.router
import be.doeraene.components.RouteDefinitions._

import scala.concurrent.ExecutionContext.Implicits.global

object WaitingRoom:

  import ServerToClientWaitingRoom._
  import ClientToServerWaitingRoom.PlayAgainst

  /** Component in the waiting room, for user `username`. If `maybeJoinGameDirectly` is defined, it will ask to play
    * against the given opponent right away.
    */
  def apply(username: String, maybeJoinGameDirectly: Option[String]) = {
    val socket = JsonWebSocket[ServerToClientWaitingRoom, ClientToServerWaitingRoom](websocketWaitingRoom)

    val configChanges: EventStream[true] = socket.inEvents.collect { case ConfigurationUpdate =>
      true
    }

    val connectedPlayersEvents = EventStream.merge(
      EventStream.fromFuture(connectedPlayersInWaitingRoom),
      configChanges.flatMapSwitch(_ => EventStream.fromFuture(connectedPlayersInWaitingRoom))
    )

    val playersWaitingToPlay = EventStream.merge(
      EventStream.fromFuture(playersWaitingToPlayCall),
      configChanges.flatMapSwitch(_ => EventStream.fromFuture(playersWaitingToPlayCall))
    )

    val invitationLinkEventStream = socket.inEvents.collect { case msg: InvitationLink =>
      msg
    }

    // div(
    //   className := "WaitingRoom",
    //   h1("Challenge other players from around the world!"),
    //   CurrentlyConnectedPlayers(connectedPlayersEvents),
    //   CurrentlyWaitingToPlay(
    //     username,
    //     playersWaitingToPlay,
    //     socket.outWriter.contramap[Any](_ => ClientToServerWaitingRoom.StartWaitingForOpponent),
    //     socket.outWriter.contramap[Any](_ => ClientToServerWaitingRoom.StopWaitingForOpponent),
    //     socket.outWriter,
    //     invitationLinkEventStream,
    //     socket.outWriter
    //   ),
    //   onMountCallback(ctx => socket.open()(using ctx.owner)),
    //   onUnmountCallback(_ => socket.outWriter.onNext(ClientToServerWaitingRoom.LeaveTheRoom)),
    //   socket.openEvents.mapTo(maybeJoinGameDirectly).collect { case Some(opponent) =>
    //     PlayAgainst(opponent)
    //   } --> socket.outWriter,
    //   socket.inEvents.collect { case ServerToClientWaitingRoom.GameHasStarted(id) =>
    //     id
    //   } --> { (id: java.util.UUID) =>
    //     socket.outWriter.onNext(ClientToServerWaitingRoom.LeaveTheRoom)
    //     router.moveTo((againstHuman ? gameId).createUrlString((), id))
    //   }
    // )
    div(
      className := "WaitingRoom",
      h1("This section is under maintenance.")
    )
  }

end WaitingRoom
