package be.doeraene.components.gamecomponents

import be.doeraene.mad.game.GameBoundaries.GameType
import be.doeraene.models.AIGameOption.Difficulty
import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.*
import be.doeraene.workers.NeuralModels

object DifficultyLevelSelector {

  def apply(difficultyLevelVar: Var[Difficulty], chosenGameTypeSignal: Signal[GameType]): HtmlElement = {
    def option(difficulty: Difficulty) = Select.option.of(
      _ => s"${difficulty.value} (${difficulty.name})",
      _.value     := difficulty.value.toString,
      _.selected <-- difficultyLevelVar.signal.map(_ == difficulty)
    )

    Select(
      marginLeft := "20px",
      option(Difficulty.of(0)),
      option(Difficulty.of(1)),
      option(Difficulty.of(2)),
      option(Difficulty.of(3)),
      child.maybe <-- chosenGameTypeSignal.map(gameType =>
        Option.when(NeuralModels.isTrained(gameType))(option(Difficulty.of(4)))
      ),
      child.maybe <-- chosenGameTypeSignal.map(gameType =>
        Option.when(NeuralModels.isTrained(gameType))(option(Difficulty.of(5)))
      ),
      _.events.onChange
        .map(_.detail.selectedOption.value.toOption.get.toInt)
        .map(Difficulty.unsafe) --> difficultyLevelVar.writer,
      chosenGameTypeSignal.changes.map(gameType =>
        if NeuralModels.isTrained(gameType) then Difficulty.of(5) else Difficulty.of(3)
      ) --> difficultyLevelVar.updater[Difficulty](_.min(_))
    )
  }

}
