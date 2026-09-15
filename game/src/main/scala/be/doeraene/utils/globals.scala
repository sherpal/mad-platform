package be.doeraene.utils

import java.io.{PrintWriter, StringWriter}

def displayThrowable(throwable: Throwable): String = {
  val sw = StringWriter()
  val pw = PrintWriter(sw)
  throwable.printStackTrace(pw)
  val sStackTrace = sw.toString
  sStackTrace
}
