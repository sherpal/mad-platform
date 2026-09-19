package be.doeraene.madworker

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Float32Array

/** The slice of onnxruntime-web this needs.
  *
  * Hand-written rather than generated because it is four members, and because the interesting part is
  * not the shape of the API but the configuration below it - which is where the difference between a
  * model that loads in a browser tab and one that does not actually lives.
  */
object OnnxRuntime:

  @js.native
  @JSImport("./ort/ort.wasm.bundle.min.mjs", "Tensor")
  // Constructor parameters deliberately not named after the members they set: a native JS class only
  // uses these positionally, and giving them the same names as the `val`s below is a redefinition.
  final class Tensor(ofType: String, from: Float32Array, shaped: js.Array[Int]) extends js.Object:
    val data: Float32Array  = js.native
    val dims: js.Array[Int] = js.native

  @js.native
  trait Session extends js.Object:
    def run(feeds: js.Dictionary[Tensor]): js.Promise[js.Dictionary[Tensor]] = js.native

  @js.native
  @JSImport("./ort/ort.wasm.bundle.min.mjs", "InferenceSession")
  object InferenceSession extends js.Object:
    def create(path: String): js.Promise[Session] = js.native

  @js.native
  trait WasmFlags extends js.Object:
    var numThreads: Int   = js.native
    var wasmPaths: String = js.native
    var proxy: Boolean    = js.native

  @js.native
  trait Env extends js.Object:
    val wasm: WasmFlags  = js.native
    var logLevel: String = js.native

  @js.native
  @JSImport("./ort/ort.wasm.bundle.min.mjs", "env")
  object env extends Env

  /** Pins the runtime to a single thread and to locally served binaries.
    *
    * Threads would need `SharedArrayBuffer`, which a page only gets when it is cross-origin isolated -
    * and that needs COOP and COEP response headers, which GitHub Pages cannot set. Left to its own
    * devices the runtime tries anyway, fails, and falls back with a console error on every load; saying
    * one thread up front is both honest and quieter.
    *
    * `wasmPaths` is set because the default is a CDN. That would be a second origin to depend on for a
    * page that is otherwise entirely self-contained, and it would break offline.
    */
  def configure(assetBase: String): Unit =
    env.wasm.numThreads = 1
    env.wasm.proxy = false
    env.wasm.wasmPaths = assetBase
    env.logLevel = "warning"

end OnnxRuntime
