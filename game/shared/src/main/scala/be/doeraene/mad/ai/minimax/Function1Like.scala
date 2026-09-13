package be.doeraene.mad.ai.minimax

import be.doeraene.mad.game.{GameAction, GameState}

trait Function1Like[F, A]:
  def asFunction1(f: F): A => A

object Function1Like:
  implicit def gameActionIsFunction1LikeForGameState: Function1Like[GameAction, GameState] =
    new Function1Like[GameAction, GameState] {
      def asFunction1(f: GameAction): GameState => GameState = f.act
    }

  extension [F, A](f: F)(using function: Function1Like[F, A]) def act(a: A): A = function.asFunction1(f).apply(a)
