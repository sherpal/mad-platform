package be.doeraene.components

import com.raquo.laminar.api.L.*
import be.doeraene.mad.game.*
import org.scalajs.dom

import scala.scalajs.js

object DisplayGameState:

  private sealed trait CssClassGameSize {
    def repr: String = toString.drop(1)
  }
  private case object _5x5 extends CssClassGameSize
  private case object _6x4 extends CssClassGameSize

  private def fromGameType(gameType: GameBoundaries.GameType): CssClassGameSize = gameType match {
    case GameBoundaries._6by4        => _6x4
    case GameBoundaries._4by6        => _6x4
    case GameBoundaries._5by5        => _5x5
    case GameBoundaries.aztecDiamond => _6x4
    case _                           => throw new IllegalArgumentException(s"Unknown game type: $gameType")
  }

  private val dragThresholdPx = 6.0

  private val elementNodeType = 1

  /** A board position, represented structurally as a row/column pair so that it doesn't depend on any particular
    * [[GameState]]'s path-dependent `Position` type - this lets us freely compare positions coming from different
    * places (a dragged piece's legal actions, a DOM attribute we parsed back) without any type gymnastics.
    */
  private type RawPosition = (Int, Int)

  /** What dropping a dragged piece onto a given board cell would need to match, to figure out which [[GameAction]] that
    * drop represents: either the square itself (for a movement action), or the piece occupying it (for a
    * permutation/rotation, which are only ever legal between pieces of the same team, so never collide with a
    * movement's target square).
    */
  private type DropTarget = Either[RawPosition, GamePiece]

  private final case class DragState(
      piece: GamePiece,
      pointerId: Double,
      gameState: GameState,
      startClientX: Double,
      startClientY: Double,
      currentClientX: Double,
      currentClientY: Double,
      isDragging: Boolean,
      targets: Map[DropTarget, Vector[GameAction]]
  )

  private final case class AmbiguousChoice(
      clientX: Double,
      clientY: Double,
      actions: List[GameAction],
      gameState: GameState
  )

  /** A move that has landed on a bonus-enabling square, but whose "second part" (the row-bonus permutation/rotation)
    * hasn't been decided yet: the player sees the board as if `movement1` already happened, and can either drag a piece
    * to complete one of `bonuses`, or explicitly skip and commit `movement1` alone.
    */
  private final case class PendingBonus(
      movement1: GameAction.GamePieceMoves1,
      bonuses: Vector[GameAction.LastRowBonus]
  )

  /** Returns the [[DropTarget]] that dropping `piece` there would represent, if `action` is a way for `piece` to end up
    * there.
    */
  private def dropTargetFor(piece: GamePiece, action: GameAction, gameState: GameState): Option[DropTarget] =
    action match {
      case movement: GameAction.MovementAction if movement.piece == piece =>
        movement.finalPosition(gameState).map(pos => Left(pos.asPair))
      case GameAction.Permutation(piece1, piece2) if piece1 == piece      => Some(Right(piece2))
      case GameAction.Permutation(piece1, piece2) if piece2 == piece      => Some(Right(piece1))
      case GameAction.Rotation(piece1, piece2, piece3) if piece1 == piece => Some(Right(piece2))
      case GameAction.Rotation(piece1, piece2, piece3) if piece2 == piece => Some(Right(piece3))
      case GameAction.Rotation(piece1, piece2, piece3) if piece3 == piece => Some(Right(piece1))
      case bonus: GameAction.LastRowBonus if bonus.movement1.piece == piece =>
        bonus.movement1.finalPosition(gameState).map(pos => Left(pos.asPair))
      case _ => None
    }

  private def dragTargetsFor(piece: GamePiece, gameState: GameState): Map[DropTarget, Vector[GameAction]] =
    gameState.allValidActions.toVector
      .flatMap(action => dropTargetFor(piece, action, gameState).map(_ -> action))
      .groupMap(_._1)(_._2)

  /** Returns every [[DropTarget]] that taking `action` would visibly affect: every involved piece's own cell (be it a
    * board square or an exile-tray slot), plus the destination square for a movement. Used to preview, while hovering a
    * valid drop, not just where the dragged piece lands but where the *other* pieces a permutation/rotation/bonus
    * touches will end up too.
    */
  private def involvedDropTargets(action: GameAction, gameState: GameState): Set[DropTarget] =
    val involvedPieces: Vector[GamePiece] = action match {
      case movement: GameAction.MovementAction   => Vector(movement.piece)
      case shift: GameAction.PieceShiftingAction => shift.involvedPieces
      case bonus: GameAction.LastRowBonus        => bonus.shiftAction.involvedPieces.prepended(bonus.movement1.piece)
      case _                                     => Vector()
    }
    val pieceTargets: Set[DropTarget] = involvedPieces.map(p => Right(p): DropTarget).toSet
    val positionTargets: Set[DropTarget] =
      involvedPieces.flatMap(p => gameState.pieces.get(p).map(pos => Left(pos.asPair): DropTarget)).toSet
    val destinationTargets: Set[DropTarget] = action match {
      case movement: GameAction.MovementAction =>
        movement.finalPosition(gameState).map(pos => Left(pos.asPair): DropTarget).toSet
      case bonus: GameAction.LastRowBonus =>
        bonus.movement1.finalPosition(gameState).map(pos => Left(pos.asPair): DropTarget).toSet
      case _ => Set.empty
    }
    pieceTargets ++ positionTargets ++ destinationTargets

  /** Returns, for every piece that taking `action` would move, the [[DropTarget]] it ends up at - a board position, or
    * (only for the piece sacrificed to revive its exiled partner) its own exile-tray slot. Mirrors exactly how
    * [[GameAction.act]] itself resolves destinations (from the *pre-action* positions), so it stays correct without
    * needing to duplicate that logic's intent.
    */
  private def pieceDestinations(action: GameAction, gameState: GameState): List[(GamePiece, DropTarget)] =
    def positionOrOwnExile(mover: GamePiece, otherPiece: GamePiece): DropTarget =
      gameState.pieces.get(otherPiece).map(pos => Left(pos.asPair): DropTarget).getOrElse(Right(mover))
    action match {
      case movement: GameAction.MovementAction =>
        movement.finalPosition(gameState).toList.map(pos => movement.piece -> (Left(pos.asPair): DropTarget))
      case GameAction.Permutation(piece1, piece2) =>
        List(piece1 -> positionOrOwnExile(piece1, piece2), piece2 -> positionOrOwnExile(piece2, piece1))
      case GameAction.Rotation(piece1, piece2, piece3) =>
        List(
          piece1 -> positionOrOwnExile(piece1, piece2),
          piece2 -> positionOrOwnExile(piece2, piece3),
          piece3 -> positionOrOwnExile(piece3, piece1)
        )
      case bonus: GameAction.LastRowBonus =>
        pieceDestinations(bonus.movement1, gameState) ++
          pieceDestinations(bonus.shiftAction, bonus.movement1.act(gameState))
      case _ => Nil
    }

  private def canDragPiece(piece: GamePiece, gameState: GameState, team: Team): Boolean =
    piece.team == team && gameState.turnOfTeam == team && !gameState.ended

  /** Recognizes the one kind of ambiguity a drop is allowed to have: a plain single-square move, plus one or more
    * [[GameAction.LastRowBonus]] built on that exact same move. When that's the shape, we don't ask the player to pick
    * from a list - we let them see the move as already made and decide the optional "second part" (see
    * [[PendingBonus]]) themselves, by dragging, the same way any other permutation/rotation is done.
    */
  private def splitMovementAndBonuses(
      actions: Vector[GameAction]
  ): Option[(GameAction.GamePieceMoves1, Vector[GameAction.LastRowBonus])] =
    actions.collect { case movement: GameAction.GamePieceMoves1 => movement }.distinct.toList match {
      case movement :: Nil =>
        actions.collect { case bonus: GameAction.LastRowBonus if bonus.movement1 == movement => bonus }.toList match {
          case Nil     => None
          case bonuses => Some(movement -> bonuses.toVector)
        }
      case _ => None
    }

  /** Drag targets for the "second part" of a pending row bonus: for each candidate [[GameAction.LastRowBonus]], where
    * would dropping `piece` complete its permutation/rotation - resolved against `postMoveGameState` (the board as it
    * stands right after `movement1` happened), and mapped to the *whole* bonus action so a successful drop dispatches
    * the combined move-and-shift as the single turn it actually is.
    */
  private def dragTargetsForPendingBonus(
      piece: GamePiece,
      pending: PendingBonus,
      postMoveGameState: GameState
  ): Map[DropTarget, Vector[GameAction]] =
    pending.bonuses
      .flatMap(bonus => dropTargetFor(piece, bonus.shiftAction, postMoveGameState).map(_ -> (bonus: GameAction)))
      .groupMap(_._1)(_._2)

  /** Parses the `data-dropkey` attribute we stamp on every in-bounds cell back into the [[DropTarget]]s it represents:
    * always the cell's own position, and also the piece sitting on it when there is one.
    */
  private def dropTargetsFromElement(element: dom.Element): List[DropTarget] =
    Option(element.getAttribute("data-dropkey")).toList.flatMap(_.split(';').toList.flatMap {
      case s"pos:$row,$col"      => List(Left((row.toInt, col.toInt)))
      case s"piece:$prettyPrint" => GamePiece.fromPrettyPrint(prettyPrint).map(Right(_)).toList
      case _                     => Nil
    })

  private def dropKeyAttribute(position: RawPosition, maybePiece: Option[GamePiece]): String =
    maybePiece.fold(s"pos:${position._1},${position._2}")(piece =>
      s"pos:${position._1},${position._2};piece:${piece.prettyPrint}"
    )

  /** The drop-key for an exiled piece's tray slot: it has no board position, so it is identified by the piece alone. */
  private def exiledPieceDropKeyAttribute(piece: GamePiece): String = s"piece:${piece.prettyPrint}"

  /** Walks up from the topmost element under the given viewport point, looking for the nearest board cell carrying a
    * `data-dropkey` attribute.
    */
  private def findDropTargetsAtPoint(clientX: Double, clientY: Double): List[DropTarget] = {
    var current: dom.Node       = dom.document.elementFromPoint(clientX, clientY)
    var found: List[DropTarget] = Nil
    while (current != null && found.isEmpty && current.nodeType == elementNodeType) {
      val element = current.asInstanceOf[dom.Element]
      found = dropTargetsFromElement(element)
      current = element.parentNode
    }
    found
  }

  /** Component showing the board, from the perspective of the specified team. The board is displayed in such a way that
    * the player's side is at the bottom.
    *
    * The player whose turn it is (and matches `team`) can move a piece either by clicking on it then on one of the
    * highlighted possible actions, or by dragging it directly onto its destination square or onto another piece (for
    * permutations/rotations) - resolved actions are pushed to `dragActionObserver`.
    */
  def apply(
      boundaries: GameBoundaries,
      gameStates: Signal[GameState],
      maybeAdditionActionStream: Signal[Option[GameAction]],
      team: Team,
      hoveredPieceObserver: Observer[Option[GamePiece]],
      pieceClickObserver: Observer[Option[GamePiece]],
      maybeSelectedPieceSignal: Signal[Option[GamePiece]],
      dragActionObserver: Observer[GameAction] = Observer.empty
  ): HtmlElement =
    val images = GamePiece.pieces.map { piece =>
      piece -> img(
        className := "piece-image",
        src       := ("/" ++ RouteDefinitions.gamePieceImagePath.createPath(piece)),
        width     := "64px",
        draggable := false
      )
    }.toMap

    val blanks = (for {
      row <- 0 until boundaries.lastRow
      col <- 0 until boundaries.lastCol
      rowCol = (row, col)
    } yield rowCol -> img(src := ("/" ++ RouteDefinitions.blankPiece.createPath()), width := "64px")).toMap

    val dragStateVar: Var[Option[DragState]]             = Var(Option.empty)
    val ambiguousChoiceVar: Var[Option[AmbiguousChoice]] = Var(Option.empty)
    val pendingBonusVar: Var[Option[PendingBonus]]       = Var(Option.empty)

    // Bound on the window rather than relying on pointer capture, so that the drag keeps tracking the pointer
    // even once it leaves the piece's original cell.
    val onWindowPointerMove: js.Function1[dom.Event, Unit] = { (rawEvent: dom.Event) =>
      val ev = rawEvent.asInstanceOf[dom.PointerEvent]
      dragStateVar.update(_.map { drag =>
        if drag.pointerId != ev.pointerId then drag
        else
          val movedEnough = drag.isDragging ||
            math.hypot(ev.clientX - drag.startClientX, ev.clientY - drag.startClientY) > dragThresholdPx
          drag.copy(currentClientX = ev.clientX, currentClientY = ev.clientY, isDragging = movedEnough)
      })
    }
    val onWindowPointerUp: js.Function1[dom.Event, Unit] = { (rawEvent: dom.Event) =>
      val ev = rawEvent.asInstanceOf[dom.PointerEvent]
      dragStateVar.now().filter(_.pointerId == ev.pointerId).foreach { drag =>
        dragStateVar.set(None)
        if !drag.isDragging then pieceClickObserver.onNext(Some(drag.piece))
        else
          findDropTargetsAtPoint(ev.clientX, ev.clientY).flatMap(drag.targets.getOrElse(_, Nil)).distinct match {
            case Nil           => ()
            case action :: Nil =>
              // Any real dispatch - whether it's a plain move or the second part of a pending bonus - settles
              // whatever bonus decision was in flight.
              pendingBonusVar.set(None)
              dragActionObserver.onNext(action)
            case many =>
              splitMovementAndBonuses(many.toVector) match {
                case Some((movement, bonuses)) => pendingBonusVar.set(Some(PendingBonus(movement, bonuses)))
                case None =>
                  ambiguousChoiceVar.set(Some(AmbiguousChoice(ev.clientX, ev.clientY, many, drag.gameState)))
              }
          }
      }
    }
    val onWindowPointerCancel: js.Function1[dom.Event, Unit] = { (rawEvent: dom.Event) =>
      val ev = rawEvent.asInstanceOf[dom.PointerEvent]
      dragStateVar.now().filter(_.pointerId == ev.pointerId).foreach(_ => dragStateVar.set(None))
    }

    val dragGhost: HtmlElement = div(
      className := "drag-ghost",
      display  <-- dragStateVar.signal.map(_.exists(_.isDragging)).map(if _ then "block" else "none"),
      left     <-- dragStateVar.signal.map(_.fold("0px")(d => s"${d.currentClientX}px")),
      top      <-- dragStateVar.signal.map(_.fold("0px")(d => s"${d.currentClientY}px")),
      img(
        width := "64px",
        src <-- dragStateVar.signal.map(_.fold("")(d => "/" ++ RouteDefinitions.gamePieceImagePath.createPath(d.piece)))
      )
    )

    div(
      position := "relative",
      dragGhost,
      onMountCallback { _ =>
        dom.window.addEventListener("pointermove", onWindowPointerMove)
        dom.window.addEventListener("pointerup", onWindowPointerUp)
        dom.window.addEventListener("pointercancel", onWindowPointerCancel)
      },
      onUnmountCallback { _ =>
        dom.window.removeEventListener("pointermove", onWindowPointerMove)
        dom.window.removeEventListener("pointerup", onWindowPointerUp)
        dom.window.removeEventListener("pointercancel", onWindowPointerCancel)
      },
      // A pending bonus (or a stale ambiguity popup) only ever makes sense for the *current* turn - if the real
      // game state moves on for any other reason (the text action list, a rewind, ...), it must not linger and
      // desync the board preview from reality.
      gameStates.changes.mapTo(None) --> Observer.combine(pendingBonusVar.writer, ambiguousChoiceVar.writer),
      child.maybe <-- pendingBonusVar.signal.map(
        _.map(renderPendingBonusBanner(_, dragActionObserver, pendingBonusVar))
      ),
      child.maybe <-- gameStates.map(gameState =>
        Option.when(gameState.withInitialSpecialRule && gameState.turnNumber <= 2 && gameState.turnOfTeam == team) {
          renderPassFirstTurn(dragActionObserver.contramap(_ => GameAction.Identity(team)))
        }
      ),
      child <-- pendingBonusVar.signal
        .combineWith(maybeAdditionActionStream)
        .combineWith(gameStates)
        .map {
          // While a bonus decision is pending, the board always shows the move as already made - regardless of
          // whatever the (now largely superseded) action list might be hovering.
          case (Some(pending), _, gameState)   => pending.movement1.act(gameState)
          case (None, Some(action), gameState) => action.act(gameState)
          case (None, None, gameState)         => gameState
        }
        .map(
          renderBoard(
            _,
            images,
            blanks,
            team,
            hoveredPieceObserver,
            pieceClickObserver,
            maybeSelectedPieceSignal,
            dragStateVar,
            pendingBonusVar
          )
        ),
      child.maybe <-- ambiguousChoiceVar.signal.map(
        _.map(renderAmbiguousChoicePopup(_, dragActionObserver, ambiguousChoiceVar))
      )
    )

  private def renderAmbiguousChoicePopup(
      choice: AmbiguousChoice,
      dragActionObserver: Observer[GameAction],
      ambiguousChoiceVar: Var[Option[AmbiguousChoice]]
  ): HtmlElement =
    div(
      div(
        className := "action-choice-backdrop",
        onClick --> { (_: dom.MouseEvent) => ambiguousChoiceVar.set(None) }
      ),
      div(
        className := "action-choice-popup",
        left      := s"${choice.clientX}px",
        top       := s"${choice.clientY}px",
        choice.actions.map { action =>
          div(
            className := "action-choice-item",
            GameHistory.prettyPrintAction(action, choice.gameState),
            onClick --> { (_: dom.MouseEvent) =>
              dragActionObserver.onNext(action)
              ambiguousChoiceVar.set(None)
            }
          )
        }
      )
    )

  /** Shown once a move lands on a bonus-enabling square: the board above already previews `movement1` as done, and this
    * either waits for the player to drag a piece to complete one of `pending.bonuses`, or lets them explicitly commit
    * to just the move, no bonus.
    */
  private def renderPendingBonusBanner(
      pending: PendingBonus,
      dragActionObserver: Observer[GameAction],
      pendingBonusVar: Var[Option[PendingBonus]]
  ): HtmlElement =
    div(
      className := "pending-bonus-banner",
      span(className := "pending-bonus-text", "Row bonus available! Drag a piece to swap or rotate it..."),
      span(
        className := "pending-bonus-skip",
        "...or end your turn, no bonus",
        onClick --> { (_: dom.MouseEvent) =>
          pendingBonusVar.set(None)
          dragActionObserver.onNext(pending.movement1)
        }
      )
    )

  private def renderPassFirstTurn(
      actionObserver: Observer[Unit]
  ): HtmlElement = div(
    className := "pending-bonus-banner",
    span(className := "pending-bonus-text", "First turn, you can decide to "),
    span(className := "pending-bonus-skip", "pass the turn", onClick.mapToUnit --> actionObserver),
    span(className := "pending-bonus-text", ".")
  )

  /** A small, slightly-displaced, semi-transparent image of `piece`, previewing that it is about to land on whatever
    * cell this is added to as a child.
    */
  private def renderMovePreviewGhost(piece: GamePiece): HtmlElement =
    img(
      className := "move-preview-ghost",
      src       := ("/" ++ RouteDefinitions.gamePieceImagePath.createPath(piece)),
      draggable := false
    )

  private def renderBoard(
      gameState: GameState,
      images: Map[GamePiece, HtmlElement],
      blanks: Map[(Int, Int), HtmlElement],
      team: Team,
      hoveredPieceObserver: Observer[Option[GamePiece]],
      pieceClickObserver: Observer[Option[GamePiece]],
      maybeSelectedPieceSignal: Signal[Option[GamePiece]],
      dragStateVar: Var[Option[DragState]],
      pendingBonusVar: Var[Option[PendingBonus]]
  ) = {
    val boundaries = gameState.gameBoundaries

    val cssClass = fromGameType(gameState.gameType)

    def symmetry[A]: List[A] => List[A] = if team == Team.Blue then _.reverse else identity

    def isBlackSquare(rowIndex: Int, colIndex: Int) =
      className := (if (rowIndex + colIndex) % 2 == 0 then "black-square-" ++ cssClass.repr else "white-square")

    def dropHighlightClass(candidates: List[DropTarget]): Signal[String] =
      dragStateVar.signal.map {
        case Some(drag) if drag.isDragging && candidates.exists(drag.targets.contains) => "drop-target-highlight"
        case _                                                                         => ""
      }

    // Computed once and shared by every cell below, so that the (cheap, but pointless to repeat per-cell)
    // elementFromPoint lookup backing it runs once per pointer move rather than once per cell per pointer move.
    val hoveredActionSignal: Signal[Option[GameAction]] = dragStateVar.signal.map {
      case Some(drag) if drag.isDragging =>
        findDropTargetsAtPoint(drag.currentClientX, drag.currentClientY)
          .flatMap(drag.targets.getOrElse(_, Nil))
          .distinct match {
          case action :: Nil => Some(action)
          case _             => None
        }
      case _ => None
    }
    val hoveredInvolvedTargetsSignal: Signal[Set[DropTarget]] =
      hoveredActionSignal.map(_.fold(Set.empty[DropTarget])(involvedDropTargets(_, gameState)))

    def hoverPreviewClass(candidates: List[DropTarget]): Signal[String] =
      hoveredInvolvedTargetsSignal.map(targets =>
        if candidates.exists(targets.contains) then "hover-move-preview" else ""
      )

    // Where every *other* piece a hovered permutation/rotation/bonus touches will actually end up - the dragged
    // piece's own destination is excluded, since it already has its own ghost following the pointer.
    val previewMovesSignal: Signal[List[(GamePiece, DropTarget)]] =
      dragStateVar.signal.combineWith(hoveredActionSignal).map {
        case (Some(drag), Some(action)) => pieceDestinations(action, gameState).filterNot(_._1 == drag.piece)
        case _                          => Nil
      }

    def moveDestinationPreview(candidates: List[DropTarget]): Signal[Option[GamePiece]] =
      previewMovesSignal.map(_.collectFirst { case (piece, dest) if candidates.contains(dest) => piece })

    // Dims the piece currently sitting here whenever some other piece is being previewed as about to land right on
    // top of it, so it reads as "this one is about to be replaced" rather than looking like it's staying put.
    def occupantDimClass(candidates: List[DropTarget]): Signal[String] =
      moveDestinationPreview(candidates).map(_.fold("")(_ => "dimmed-occupant"))

    def pieceDragStartHandler(piece: GamePiece): Modifier[HtmlElement] =
      onPointerDown --> { (ev: dom.PointerEvent) =>
        ev.preventDefault()
        // While a bonus decision is pending, `gameState` here is already the post-move preview - dragging now is
        // only ever about completing (one of) its bonus shift(s), never a fresh, independent move.
        val targets = pendingBonusVar.now() match {
          case Some(pending) =>
            if piece.team == team then dragTargetsForPendingBonus(piece, pending, gameState) else Map.empty
          case None => if canDragPiece(piece, gameState, team) then dragTargetsFor(piece, gameState) else Map.empty
        }
        dragStateVar.set(
          Some(
            DragState(piece, ev.pointerId, gameState, ev.clientX, ev.clientY, ev.clientX, ev.clientY, false, targets)
          )
        )
      }

    val withBorderCls = "with-border"
    val rows =
      gameState.gameBoundaries.rows.toList.zipWithIndex.map { (row, rowIndex) =>
        tr(
          td(className := "row-index", (boundaries.lastRow - rowIndex).toString),
          symmetry(row.toList.zipWithIndex.map {
            case (None, colIndex) =>
              td(
                className := withBorderCls,
                className := "illegal-square",
                blanks((rowIndex, colIndex)),
                onMouseEnter.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                onMouseLeave.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                onClick.mapTo(None) --> pieceClickObserver
              )
            case (Some(position), colIndex) =>
              gameState.piecesFromPosition.get(position) match {
                case None =>
                  val candidates = List(Left(position.asPair): DropTarget)
                  td(
                    isBlackSquare(rowIndex, colIndex),
                    className           := withBorderCls,
                    dataAttr("dropkey") := dropKeyAttribute(position.asPair, None),
                    className          <-- dropHighlightClass(candidates),
                    className          <-- hoverPreviewClass(candidates),
                    child.maybe        <-- moveDestinationPreview(candidates).map(_.map(renderMovePreviewGhost)),
                    blanks((rowIndex, colIndex)),
                    onMouseEnter.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                    onMouseLeave.mapTo(Option.empty[GamePiece]) --> hoveredPieceObserver,
                    onClick.mapTo(None) --> pieceClickObserver
                  )
                case Some(piece) =>
                  val candidates = List(Left(position.asPair): DropTarget, Right(piece): DropTarget)
                  td(
                    isBlackSquare(rowIndex, colIndex),
                    className           := withBorderCls,
                    dataAttr("dropkey") := dropKeyAttribute(position.asPair, Some(piece)),
                    className          <-- dropHighlightClass(candidates),
                    className          <-- hoverPreviewClass(candidates),
                    className          <-- occupantDimClass(candidates),
                    child.maybe        <-- moveDestinationPreview(candidates).map(_.map(renderMovePreviewGhost)),
                    images(piece),
                    onMouseEnter.mapTo(Some(piece)) --> hoveredPieceObserver,
                    onMouseLeave.mapTo(None) --> hoveredPieceObserver,
                    pieceDragStartHandler(piece),
                    className <-- maybeSelectedPieceSignal
                      .map(_.contains(piece))
                      .map(pieceIsSelected =>
                        if pieceIsSelected then s"piece-selected-${piece.team.toString.toLowerCase}" else ""
                      )
                  )
              }
          })
        )
      }

    val colNames = boundaries.alphabet.map(char => th(char.toUpper.toString)).take(boundaries.lastCol).toList

    // Own exiled pieces are shown so that a permutation/rotation bringing one of them back onto the board (the
    // rules allow this whenever enough of the other pieces involved are still alive) has something to drag onto/from.
    val exiledOwnPieces: List[GamePiece] =
      GamePiece.pieces
        .filter(piece => piece.team == team && !gameState.pieceIsAlive(piece))
        .toList
        .sortBy(_.prettyPrint)

    def exiledPieceCell(piece: GamePiece): HtmlElement = {
      val candidates = List(Right(piece): DropTarget)
      div(
        className           := "exile-cell with-border",
        dataAttr("dropkey") := exiledPieceDropKeyAttribute(piece),
        className          <-- dropHighlightClass(candidates),
        className          <-- hoverPreviewClass(candidates),
        className          <-- occupantDimClass(candidates),
        child.maybe        <-- moveDestinationPreview(candidates).map(_.map(renderMovePreviewGhost)),
        images(piece),
        onMouseEnter.mapTo(Some(piece)) --> hoveredPieceObserver,
        onMouseLeave.mapTo(None) --> hoveredPieceObserver,
        pieceDragStartHandler(piece),
        className <-- maybeSelectedPieceSignal
          .map(_.contains(piece))
          .map(pieceIsSelected => if pieceIsSelected then s"piece-selected-${piece.team.toString.toLowerCase}" else "")
      )
    }

    div(
      table(
        className := "game-state-table",
        thead(tr(th(), symmetry(colNames))),
        tbody(symmetry(rows))
      ),
      if exiledOwnPieces.nonEmpty then
        div(
          className := "exile-tray",
          // span(className := "exile-tray-label", "Exiled pieces:"),
          exiledOwnPieces.map(exiledPieceCell)
        )
      else span()
    )
  }
