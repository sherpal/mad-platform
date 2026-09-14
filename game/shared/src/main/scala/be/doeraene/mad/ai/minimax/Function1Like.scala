package be.doeraene.mad.ai.minimax

import be.doeraene.mad.game.{GameAction, GameState}

trait Function1Like[F, A]:
  def asFunction1(f: F): A => A

object Function1Like:
  given Function1Like[GameAction, GameState] = _.act

  extension [F, A](f: F)(using function: Function1Like[F, A]) def act(a: A): A = function.asFunction1(f).apply(a)
