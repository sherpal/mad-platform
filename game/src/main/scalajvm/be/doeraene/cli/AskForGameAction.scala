package be.doeraene.cli

import be.doeraene.mad.game.{GameAction, GameState}

import scala.util.Try

object AskForGameAction extends UserInteraction[GameState, GameAction]:

  def prettyPrintFromInput(input: GameState): String =
    s"""
       |Choose among the following actions (enter its id and press enter):
       |${input.allValidActions
      .map(_.prettyPrint(input))
      .zipWithIndex
      .map((action: String, idx: Int) => s"$idx: $action")
      .mkString("\n")}
       |""".stripMargin

  def parseInput(str: String, input: GameState): Either[String, GameAction] =
    for {
      actionIndex <- Try(str.toInt).toEither.swap.map(_ => s"`$str` is not a well formed integer").swap
      validActions = input.allValidActions.toVector
      _ <- Either.cond(
        validActions.indices.contains(actionIndex),
        (),
        s"Index $actionIndex is out of bound [0, ${validActions.length - 1}]"
      )
      action = validActions(actionIndex)
    } yield action

end AskForGameAction
