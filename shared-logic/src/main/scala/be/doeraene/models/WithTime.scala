package be.doeraene.models

import cats.Monoid
import io.circe.{Decoder, Encoder}

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

final case class WithTime[Value](value: Value, time: WithTime.time.Time) {
  def map[To](f: Value => To): WithTime[To] = WithTime(f(value), time)
}

object WithTime {
  def start[Value](value: Value): WithTime[Value] = WithTime(value, time.Time.zero)

  given [V](using Encoder[V]): Encoder[WithTime[V]] = io.circe.generic.semiauto.deriveEncoder
  given [V](using Decoder[V]): Decoder[WithTime[V]] = io.circe.generic.semiauto.deriveDecoder

  object time {
    opaque type Time = Long

    object Time {
      def zero: Time = 0L
      
      def now(): Time = System.currentTimeMillis()

      def fromValue(now: Long): Time = now

      def fromLocalDateTime(startTime: LocalDateTime, now: LocalDateTime): Time =
        startTime.until(now, ChronoUnit.MILLIS)

      extension (time: Time) {
        def value: Long = time
        
        def until(that: Time): Time = that - time

        def toSeconds: Long = time / 1000
        def toMinutes: Long = time.toSeconds / 60

        def format: String = {
          val minutes = time.toMinutes
          val seconds = time.toSeconds % 60
          String.format("%02d", minutes) ++ ":" ++ String.format("%02d", seconds)
        }

        def -(that: Time): Time = time - that
        def +(that: Time): Time = time + that
      }

      given Monoid[Time] = new Monoid[Time] {
        override def empty: Time = zero

        override def combine(x: Time, y: Time): Time = x + y
      }

      given Encoder[Time] = Encoder.encodeLong
      given Decoder[Time] = Decoder.decodeLong
    }
  }

}
