package be.doeraene.workers

import scala.scalajs.js

sealed abstract class ProtocolException(message: String) extends RuntimeException(message)

object ProtocolException:

  class ReceivedDataWasNull extends ProtocolException(s"The received data was null.")

  class ReceivedNonStringData(data: Any) extends ProtocolException(s"I received a message with non string data.")

  class DecodingError(val error: Exception) extends ProtocolException(error.getMessage)

  class WrongReturnedValue(val sent: WorkerProtocol, val received: WorkerProtocol)
    extends ProtocolException(s"I sent $sent but I received $received back.")

  class DomErrorWrapper(val error: js.Any) extends ProtocolException(
    s"Serialization of ErrorEvent: ${js.JSON.stringify(error)}"
  )

end ProtocolException
