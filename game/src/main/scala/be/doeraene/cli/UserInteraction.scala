package be.doeraene.cli

trait UserInteraction[Input, Output]:

  def prettyPrintFromInput(input: Input): String
  
  def parseInput(str: String, input: Input): Either[String, Output]
  
  final def askForOutputFrom(input: Input): Output =
    println(prettyPrintFromInput(input))
    val in = scala.io.StdIn.readLine()
    parseInput(in, input) match {
      case Left(value) =>
        println(value)
        askForOutputFrom(input)
      case Right(value) =>
        value
    }

end UserInteraction
