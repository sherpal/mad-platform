package be.doeraene.madworker

import be.doeraene.mad.game.*
import be.doeraene.mad.ai.minimax.*
import be.doeraene.mad.ai.Player

import org.scalajs.dom.DedicatedWorkerGlobalScope.self
import org.scalajs.dom.URL

/** Where the site is served from, worked out from the worker's own script URL.
  *
  * Not hard-coded and not root-relative: the site lives under a base path on GitHub Pages and at the
  * root under `vite dev`, so anything absolute is wrong in one of the two. The worker script is at
  * `<base>/web-worker/main.mjs`, so its parent's parent is the base, and letting `URL` do that
  * resolution keeps the `..` out of the fetched address.
  */
private def siteRoot: String = URL("../", self.location.href).href

/** The directory this worker's own script lives in. */
private def workerRoot: String = URL("./", self.location.href).href

/** The network the browser plays with, produced by `python/export_onnx.py`. Committed, so it lives with
  * the rest of the site's assets.
  */
def modelUrl: String = siteRoot + "nn/mad.onnx"

/** Where onnxruntime-web's own `.wasm` binaries are served from; see [[OnnxRuntime.configure]].
  *
  * Next to the worker rather than with the model, because the worker imports the runtime by the relative
  * path `./ort/...` - the only kind of specifier a browser can resolve in a file Vite never processed.
  * Keeping the binaries beside the module that loads them is the same reason.
  */
def ortAssetBase: String = workerRoot + "ort/"

def handleCurrentGSWithAction(
    gameState: GameState,
    action: GameAction,
    aValue: Double,
    turnAhead: Int
): Double = {

  def handle(state: GameState) = {
    val node = Node.MadGameStateNode(state)
    given TreeExplorer[GameState, GameAction, Team] =
      // Player.jPaulDoeTheoryTreeExplorer(aValue)(state)
      Player.tacticalTreeExplorer
    node.scoreForAction(action, Node.MadGameStateNode(action(state)), state.turnOfTeam, turnAhead)
  }

  handle(gameState)

}
