# MAD

# MAD game AI

This project implements the rule (nearly all of it) of the MAD board game.

It also implements a minimax-based AI algorithm to play the game. The correct game state evaluation function is still not found...

In order to play the game against the AI, you need to follow the following steps:

1. If you don't have [sbt](https://www.scala-sbt.org/) yet installed, you should first install it
2. In a terminal (command line) window, go to this project directory (the Mad directory, not the `project` directory in `Mad`)
3. run `sbt` command to launch the sbt console
4. Inside the console (this can take some time), first run the `gameJVM/compile` command (this step is optional, it will be done automatically afterwards anyway)
5. Then still inside the sbt console, run `gameJVM/run play random 0.03 5` to launch a game against the AI where colours will be randomly chosen, and the minimax AI will compute 5 turns ahead.

Other options are available, run `gameJVM/run` in sbt to see them (there will be an error stack trace, but instructions are just above).

## Modifying the code

We recommend importing the project inside [Intellij](https://www.jetbrains.com/idea/). You need to have the Scala plugin installed (`File > Preferences > Plugins`).

Once you have IntelliJ installed, you can import this project by `File > Open` and selecting the file `build.sbt`. If the Scala plugin is correctly installed, IntelliJ should ask you whether you want to open it as project, which you should do.

## Loading a game

If you have a file describing a given game configuration (either theoretical, or resulting from an actual game), you can load that configuration and start the game from there. In order to do so, you need to run

```
gameJVM/run play <player-color> <a-value> <turn-ahead> <path-to-file>
```

For example, in the `data` folder, there is a file called `example-input-gamestate.txt` which means that the game can be started from there via

```
gameJVM/run play red 0.03 5 ./data/example-input-gamestate.txt
```

The format of the file must be the following:

- each line is either empty, or a key value pair separated by a colon
- there should be the field `Turn Number`, which is an Int
- For each position in the board where there is a piece, you have to specify the key as the "chess" position, and the piece as its "pretty print". For example, `C4: Blue212`.

Have a look in [here](./data/example-input-gamestate.txt) for a working example.

Note: the turn number determines whose turn it is. An Odd number means that Red is playing, while an Even number means that Blue is playing.

## Run the server locally

You need to have sbt and node.js installed. You need three terminals (command line), two with the sbt console launched and one in the "frontend" directory. In the frontend console, run `npm install` (this is a one time process).

In one sbt console, run `server/run`. In the other one, run `~frontend/fastLinkJS`. In the console without sbt, run `npx snowpack dev` which will redirect you to your browser on `localhost:8080`. If it's the first time you connect, you will first need to authenticate using one of the credentials. You can directly go to `localhost:9000/login` to do so.

Each time you touch to the `worker` sub-project, you need to `fastOptWorker` and then relaunch the server.

## To deploy on Heroku

```
sbt buildApplication
heroku login
heroku deploy:jar server/target/scala-3.3.1/server-assembly-0.1.0-SNAPSHOT.jar --app mad-game-platform
```

## Adding a new game type

- Add a new concrete case class in the `GameBoundaries.scala` companion object
- Add the corresponding `GameType`
- Add the matching `css` class in `DisplayGameState.scala`
- Add the new selection option in `AINewGameView.scala` 
