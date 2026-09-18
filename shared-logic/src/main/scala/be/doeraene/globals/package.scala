package be.doeraene

import urldsl.language.dummyErrorImpl.*
import be.doeraene.models.GameHistory

package object globals {

  private val api = root / "api"
  private val ws  = root / "ws"

  val webWorkerPath = "/mad-the-game/web-worker/main.mjs"

  val madRulesPath = "http://www.doeraene.be/mad/MAD-rules.pdf"

  val mePath = api / "me"

  private val beforeJoin       = api / "before-join"
  val playersInWaitingRoomPath = beforeJoin / "connected-people"
  val playersWaitingToPlayPath = beforeJoin / "waiting-to-play"
  val websocketWaitingRoom     = ws / "before-join"
  val websocketPlayingRoom     = ws / "playing-room"

  private val playingRoom = api / "playing-room"
  val doesGameExistsPath  = playingRoom / "game-id-exists"

}
