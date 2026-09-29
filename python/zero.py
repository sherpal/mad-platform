#!/usr/bin/env python3
"""Trains a network with no teacher at all - no minimax, no harvest, nothing but the rules.

`pipeline.py` starts by imitating the hand-written engine, which is what AlphaGo did. This starts from a
network that has never seen a game, which is what AlphaGo *Zero* did. The only thing it is ever told is
who won.

    .venv/bin/python zero.py --board 6x4 --until 08:00

Why it is plausible here, in one number: **68% of random-vs-random games end in a real win** (W61 L50
D51 over 162 games on 6x4). Regicide is a single capture, so it happens by accident, and a network that
has learnt nothing still produces decisive games to learn from. That is the cold start chess does not
have, where random games nearly all draw and the value head has nothing to fit.

Three things differ from pipeline.py, and all three exist because generation 1 here is random play:

  * **Simulations ramp up.** 600 simulations over 153 actions is wasted on a network with no opinions -
    the search has nothing to guide it. Early generations run few and cheap, and the count climbs as the
    network becomes worth searching over.
  * **The value target is the game result alone** until the network is worth listening to. Blending in
    the search's own root value, as pipeline.py does, would be blending in noise while the network is
    still random.
  * **Training uses a sliding window of recent generations**, not just the last one, so small cheap
    generations do not make the network chase whatever its latest batch happened to contain.

The run stops cleanly at `--until` and can be resumed with the identical command: everything needed to
carry on lives in state.json, so a resume costs nothing and re-runs nothing.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

import pipeline
from pipeline import (BOARDS, Fatal, Report, ROOT, arena, benchmark, check_environment, check_harvest,
                      check_fingerprints, heading, python_step, say, sbt, score_of)

# --------------------------------------------------------------------------------------------------
# The schedule
# --------------------------------------------------------------------------------------------------

# (from this generation onwards, use this many simulations). A network with no opinions cannot use a
# deep search, and a network with good opinions is wasted on a shallow one; this is the ramp between.
SIMULATION_RAMP = [(1, 128), (5, 192), (9, 256), (13, 352), (18, 480), (24, 600)]


def simulations_for(generation: int, cap: int) -> int:
    chosen = SIMULATION_RAMP[0][1]
    for start, count in SIMULATION_RAMP:
        if generation >= start:
            chosen = count
    return min(chosen, cap)


def blend_for(generation: int, blend_after: int, blend: float) -> float:
    """How much of the value target comes from the search rather than from who actually won.

    Zero until the network's own search is worth quoting. The search's root value is an average over
    leaf evaluations, so while the value head is random the root value is random too, and mixing it into
    the target would just add variance to the one signal that is genuinely reliable: the result.
    """
    return blend if generation > blend_after else 0.0


def gate_for(generation: int, settle: int) -> float:
    """The arena score a challenger needs to take over as champion.

    Lower early, on purpose. AlphaZero dropped gating altogether; AlphaGo Zero gated at 55%. Early here
    both networks are weak enough that 40 games cannot resolve a 55% difference, so a strict gate mostly
    rejects real progress for noise. What the gate is really protecting against at this stage is a
    catastrophic regression, so it only has to catch a challenger that is clearly *worse*.
    """
    return 0.45 if generation <= settle else 0.55


# --------------------------------------------------------------------------------------------------
# Resumable state
# --------------------------------------------------------------------------------------------------


class State:
    """Everything needed to carry on, written after every generation.

    The pipeline it grew out of resumes by looking at which directories exist, which works but re-runs
    every arena to rediscover which network is champion. Over the tens of generations a zero run needs,
    that is half an hour of recomputation to learn something it already knew. This writes it down.
    """

    def __init__(self, path: Path, board: str) -> None:
        self.path = path
        self.board = board
        self.generation = 0
        self.champion = "gen0-model"
        self.history: list[dict] = []
        self.notes: list[str] = []
        self.wall_clock = 0.0
        # Which minimax depth is the real bar on this board. A property of the hand-written engine, so
        # it is measured once and remembered - the comparison costs minutes and never changes.
        self.depth: int | None = None
        # Whether generation 0 has passed its sanity check. Recorded rather than inferred from the file
        # existing, because the check is fatal and the model is written before it runs: inferring would
        # mean a resume silently skips the one check that is there to stop a night being spent on a
        # broken export.
        self.gen0_checked = False

    @classmethod
    def load_or_new(cls, path: Path, board: str) -> "State":
        state = cls(path, board)
        if not path.exists():
            return state
        saved = json.loads(path.read_text())
        if saved["board"] != board:
            raise Fatal(f"{path} is a run of the {saved['board']} board, not {board}")
        state.generation = saved["generation"]
        state.champion = saved["champion"]
        state.history = saved["history"]
        state.notes = saved["notes"]
        state.wall_clock = saved.get("wall_clock", 0.0)
        state.depth = saved.get("depth")
        state.gen0_checked = saved.get("gen0_checked", False)
        return state

    def save(self) -> None:
        # Written to a sibling and moved into place: a run killed mid-write would otherwise leave a
        # truncated state.json, and the whole point of this file is to be trustworthy after a kill.
        scratch = self.path.with_suffix(".json.writing")
        scratch.write_text(json.dumps({
            "board": self.board,
            "generation": self.generation,
            "champion": self.champion,
            "history": self.history,
            "notes": self.notes,
            "wall_clock": self.wall_clock,
            "depth": self.depth,
            "gen0_checked": self.gen0_checked,
        }, indent=2))
        scratch.replace(self.path)


# --------------------------------------------------------------------------------------------------
# The deadline
# --------------------------------------------------------------------------------------------------


def deadline_from(text: str | None, hours: float | None) -> float | None:
    """Turns `--until 08:00` or `--hours 12` into a wall-clock instant."""
    if hours is not None:
        return time.time() + hours * 3600
    if text is None:
        return None
    hour, _, minute = text.partition(":")
    now = datetime.now()
    when = now.replace(hour=int(hour), minute=int(minute or 0), second=0, microsecond=0)
    if when <= now:
        when += timedelta(days=1)
    return when.timestamp()


def time_remains(deadline: float | None, needed: float, report_to: list[str]) -> bool:
    """Whether there is room for another generation of roughly `needed` seconds.

    Checked before starting one rather than interrupting one in progress: a generation killed halfway
    leaves a half-written harvest, and the next run would have to throw it away. Stopping between
    generations means every resume starts from a clean boundary.
    """
    if deadline is None:
        return True
    left = deadline - time.time()
    if left <= 0:
        report_to.append("the deadline has passed")
        return False
    if left < needed * 1.1:
        report_to.append(
            f"{left / 60:.0f} min left and the last generation took {needed / 60:.0f} min - "
            "stopping here rather than starting one that would be cut off"
        )
        return False
    return True


# --------------------------------------------------------------------------------------------------


def descriptor_for(board: str, data: Path) -> Path:
    """Asks Scala for the network's shape on this board, and keeps the answer next to the run."""
    path = data / "descriptor.json"
    if path.exists():
        return path
    output = sbt(f"game/run nn-descriptor {board}", what="reading the network descriptor")
    start = output.index("{")
    end = output.rindex("}") + 1
    text = output[start:end]
    json.loads(text)  # fail here, with the text in hand, rather than later somewhere confusing
    path.write_text(text + "\n")
    return path


def make_random_network(board: str, data: Path, state: "State", report: Report, force: bool) -> Path:
    """Generation 0: the right shape, no knowledge."""
    model = data / "gen0-model"
    if (model / "model.onnx").exists() and state.gen0_checked:
        say(f"{model} already present and checked, skipping")
        return model

    descriptor = descriptor_for(board, data)
    if not (model / "model.onnx").exists():
        heading("generation 0: a network that has never seen a game")
        python_step("random_init.py", "--descriptor", str(descriptor), "--out", str(model),
                    what="building the random network")
        python_step("export_onnx.py", str(model / "model.pt"), what="exporting the random network")
    check_fingerprints_against_descriptor(descriptor, model, force)

    # A broken export or encoding would show up here, before a night is spent on top of it. What it can
    # and cannot prove is worth being precise about: an empty network has no way to *construct* a win, so
    # it draws most games and scores near 50% no matter how healthy it is - 50% here was W0 L0 D12. The
    # signal is therefore losses, not wins. A search reading a scrambled position loses to a random
    # player; one reading a correct position does not, whatever else it fails to do.
    heading("checking the random network really is uninformed")
    neutral = sbt(f'game/run nn-benchmark {model}/model.onnx 200 3 {{"Random":{{}}}} 12 42 16 {board}',
                  what="the random network against a random player")
    neutral_score = score_of(neutral, "the random network's score against a random player")
    wins, losses, draws = pipeline.wld_of(neutral)
    say(f"         against a random player: W{wins} L{losses} D{draws} ({neutral_score:.0%}) - "
        "mostly draws is the expected shape, because search with no knowledge can avoid losing but "
        "cannot build a win")
    if neutral_score < 0.35:
        pipeline.fatal(
            f"search over the empty network *loses* to a player moving at random (W{wins} L{losses} "
            f"D{draws}). Avoiding an immediate loss is the one thing search can do with no knowledge at "
            "all, so this points at the encoding or the export rather than at the network.",
            force,
        )
    if neutral_score < 0.5:
        report.warn(
            f"generation 0 scored {neutral_score:.0%} against a random player (W{wins} L{losses} "
            f"D{draws}). Not fatal, but it is the wrong side of even - worth a look if the run goes "
            "nowhere."
        )
    report.note(f"generation 0 (no knowledge, search only) vs random: W{wins} L{losses} D{draws}")
    state.gen0_checked = True
    state.save()
    return model


def check_fingerprints_against_descriptor(descriptor: Path, model: Path, force: bool) -> None:
    theirs = json.loads(descriptor.read_text())["actionFingerprint"]
    ours = json.loads((model / "model.json").read_text())["actionFingerprint"]
    if theirs != ours:
        pipeline.fatal(f"action ordering mismatch: descriptor {theirs}, model {ours}", force)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--board", required=True, choices=BOARDS)
    parser.add_argument("--until", default=None, metavar="HH:MM",
                        help="stop before the generation that would run past this time of day")
    parser.add_argument("--hours", type=float, default=None, help="stop after about this many hours")
    parser.add_argument("--generations", type=int, default=200,
                        help="a ceiling, not a target; the deadline is normally what stops the run")
    parser.add_argument("--games", type=int, default=2000,
                        help="self-play games per generation. A quarter of what pipeline.py harvests, "
                             "because a cold start needs many policy-improvement steps far more than it "
                             "needs big ones, and the training window below is what keeps small "
                             "generations stable. Measured: 2000 games at 128 simulations is about 23 "
                             "minutes on 20 cores, and yields ~29 positions per game.")
    parser.add_argument("--window", type=int, default=4,
                        help="how many recent generations to train on at once")
    parser.add_argument("--simulations", type=int, default=600,
                        help="the ceiling the simulation ramp climbs to")
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--value-blend", type=float, default=0.35,
                        help="how much of the value target comes from the search, once the network is "
                             "worth listening to at all")
    parser.add_argument("--blend-after", type=int, default=10,
                        help="generation from which --value-blend starts being applied")
    parser.add_argument("--settle", type=int, default=8,
                        help="generations to run on the lenient arena gate before tightening it")
    parser.add_argument("--benchmark-depth", type=int, default=None,
                        help="skip measuring which minimax depth is strongest and use this one. The "
                             "comparison costs ~20 minutes because depth 5 is slow, and the answer is a "
                             "fixed property of the board: it is 4 on 6x4, 5x5 and 4x6.")
    parser.add_argument("--benchmark-every", type=int, default=4,
                        help="score the champion against the minimax this often. A yardstick only - "
                             "nothing measured here ever reaches the network.")
    parser.add_argument("--restart", action="store_true")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    data = ROOT / "data" / f"nn-zero-{args.board}"
    report = Report()
    started = time.time()
    deadline = deadline_from(args.until, args.hours)

    if args.restart and data.exists():
        say(f"--restart: deleting {data}")
        shutil.rmtree(data)
    data.mkdir(parents=True, exist_ok=True)

    state = State.load_or_new(data / "state.json", args.board)

    say("=" * 78)
    say(f"Tabula rasa on the {args.board} board - no minimax, no harvest, only the rules")
    say(f"  generations  {args.games} games each, training on the last {args.window}")
    say(f"  simulations  ramping {SIMULATION_RAMP[0][1]} -> {args.simulations}")
    if deadline:
        say(f"  until        {datetime.fromtimestamp(deadline):%a %H:%M} "
            f"({(deadline - time.time()) / 3600:.1f} hours from now)")
    say(f"  output       {data}")
    if state.generation:
        say(f"  RESUMING     at generation {state.generation + 1}, champion {state.champion}, "
            f"{state.wall_clock / 3600:.1f} hours spent so far")
    say("=" * 78)

    check_environment(report, data, args.force)
    make_random_network(args.board, data, state, report, args.force)

    # Measured once and reused: which minimax depth is the real bar on this board. Purely a yardstick -
    # see --benchmark-every. Nothing the minimax says is ever trained on.
    if state.depth is None:
        if args.benchmark_depth is not None:
            state.depth = args.benchmark_depth
            report.note(f"benchmarking against depth {state.depth}, given rather than measured")
        else:
            state.depth = pipeline.choose_benchmark_depth(args.board, report)
        state.save()
    depth = state.depth

    stopped_because: list[str] = []
    # What one generation costs, for deciding whether another one fits before the deadline. Seeded from
    # the last generation of a previous run when there is one, because a resume that guesses low starts a
    # generation it cannot finish, and a resume that guesses high stops with hours left on the clock.
    typical = state.history[-1]["minutes"] * 60 if state.history else 25 * 60.0
    typical_sims = state.history[-1]["simulations"] if state.history else SIMULATION_RAMP[0][1]

    while state.generation < args.generations:
        generation = state.generation + 1
        sims = simulations_for(generation, args.simulations)
        # Self-play dominates a generation and its cost is linear in simulations, so a generation that
        # follows a step in the ramp costs proportionally more than the one before it. Scaling the
        # estimate keeps the run from starting a 50-minute generation with 30 minutes left, which is
        # what a flat "same as last time" estimate does every time the ramp steps up.
        if not time_remains(deadline, typical * sims / typical_sims, stopped_because):
            break

        blend = blend_for(generation, args.blend_after, args.value_blend)
        gate = gate_for(generation, args.settle)
        began = time.time()

        heading(f"generation {generation} - {args.games} games at {sims} simulations, "
                f"value blend {blend:g}, arena gate {gate:.0%}")
        champion = data / state.champion
        harvest = data / f"gen{generation}"
        pipeline.self_play(args.board, champion, harvest, args.games, sims, generation * 1000)
        info = check_harvest(harvest, report, args.force, f"generation {generation}")

        window = [data / f"gen{g}" for g in range(max(1, generation - args.window + 1), generation + 1)]
        window = [path for path in window if (path / "manifest.json").exists()]
        candidate = data / f"gen{generation}-model"
        if not (candidate / "model.onnx").exists():
            # value-scale is required by train.py but unused for self-play shards, whose root values are
            # already expected outcomes rather than evaluator units. Passed as 1 to say so.
            pipeline.train(window, candidate, 1.0, report, init=champion, epochs=args.epochs,
                           lr=args.lr, label=f"generation {generation}",
                           extra=["--value-blend", str(blend)])
        else:
            say(f"{candidate} already present, skipping training")
        check_fingerprints(harvest, candidate, args.force)

        _, arena_score = arena(args.board, candidate, champion, sims, report)
        promoted = arena_score >= gate
        say(f"         arena against the champion: {arena_score:.1%} against a {gate:.0%} gate - "
            f"{'promoted' if promoted else 'rejected'}")

        entry = {"generation": generation, "simulations": sims, "blend": blend,
                 "positions": info["positions"], "arena": arena_score, "promoted": promoted,
                 "depth": depth, "minutes": 0.0}

        if promoted:
            state.champion = candidate.name
        if generation % args.benchmark_every == 0 or generation == 1:
            entry["benchmark"] = benchmark(args.board, data / state.champion, depth, 800,
                                           10, f"generation {generation}")
            say(f"         champion against depth-{depth} minimax at 800 sims: {entry['benchmark']:.1%}")

        entry["minutes"] = (time.time() - began) / 60
        typical, typical_sims = time.time() - began, sims
        state.generation = generation
        state.history.append(entry)
        state.wall_clock += time.time() - began
        state.save()
        say(f"         generation {generation} took {entry['minutes']:.0f} min; "
            f"champion is {state.champion}")

    if state.generation >= args.generations:
        stopped_because.append(f"reached the --generations ceiling of {args.generations}")

    # ---- what happened ---------------------------------------------------------------------------
    heading("summary")
    say(f"board       {args.board} (tabula rasa)")
    say(f"champion    {data / state.champion}")
    say(f"this run    {(time.time() - started) / 3600:.1f} hours")
    say(f"cumulative  {state.wall_clock / 3600:.1f} hours over {state.generation} generations")
    for reason in stopped_because:
        say(f"stopped     {reason}")
    say()
    say("  gen   sims  positions    arena         vs minimax")
    for entry in state.history:
        bench = f"{entry['benchmark']:>9.1%}" if "benchmark" in entry else "        -"
        mark = "" if entry["promoted"] else "  (rejected)"
        say(f"  {entry['generation']:>3}  {entry['simulations']:>5}  {entry['positions']:>9}  "
            f"{entry['arena']:>6.1%} {bench}{mark}")

    for label, lines in (("Notes", report.notes + state.notes), ("Warnings", report.warnings)):
        if lines:
            say()
            say(f"{label}:")
            for line in lines:
                say(f"  - {line}")

    say()
    say("Carry on from here with the identical command - nothing is recomputed:")
    say(f"  .venv/bin/python zero.py --board {args.board} --until HH:MM")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fatal as error:
        say()
        say(f"STOPPED: {error}")
        say("Nothing was thrown away - re-run to carry on, or pass --force to push past this check.")
        sys.exit(1)
    except KeyboardInterrupt:
        say()
        say("Interrupted. Re-run the same command to carry on from the last completed generation.")
        sys.exit(130)
