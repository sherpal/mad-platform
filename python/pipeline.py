#!/usr/bin/env python3
"""Trains a Mad network for a board, from nothing, unattended.

    cd python
    .venv/bin/python pipeline.py --board 4x6

That is the whole interface. It harvests positions from the minimax, trains on them, then runs
self-play generations until they stop improving, checking at each step that what came out is sane and
stopping - or at least saying so loudly - when it is not.

Boards: 6x4, 5x5, 4x6, aztec. Each gets its own directory under `data/nn-<board>/`, because a network's
input is 19 x rows x cols and models for different boards are not interchangeable.

It is safe to interrupt and re-run: completed stages are detected and skipped. Pass --restart to throw
away previous work for the board and begin again.

Rough costs, measured on 20 cores and a laptop GPU, for the defaults below:

    bootstrap harvest   3-6 hours   (the single longest step; scales with --bootstrap-games)
    each generation     2-3 hours
    a full run          12-24 hours

Once it finishes, ship the model by copying it next to the site and adding the board to
`NeuralModels` in shared-js, which is what tells the browser a network exists for it:

    cp data/nn-4x6/gen5-model/model.onnx frontend/public/nn/mad-4x6.onnx
    cp data/nn-4x6/gen5-model/model.json frontend/public/nn/mad-4x6.json
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

BOARDS = ["6x4", "5x5", "4x6", "aztec"]

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PYTHON = HERE / ".venv" / "bin" / "python"


# --------------------------------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------------------------------


@dataclass
class Report:
    """Everything worth saying at the end, collected as it happens.

    Warnings are gathered rather than only printed, because a 20-hour run scrolls a long way and the
    one line that mattered will be hours off the top of the terminal by the time it finishes.
    """

    warnings: list[str] = field(default_factory=list)
    generations: list[dict] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def warn(self, message: str) -> None:
        self.warnings.append(message)
        say(f"WARNING  {message}")

    def note(self, message: str) -> None:
        self.notes.append(message)
        say(f"note     {message}")


def say(message: str = "") -> None:
    stamp = time.strftime("%m-%d %H:%M:%S")
    print(f"[{stamp}] {message}" if message else "", flush=True)


def heading(message: str) -> None:
    say()
    say("-" * 78)
    say(message)
    say("-" * 78)


class Fatal(RuntimeError):
    """Something is wrong enough that continuing would waste hours producing a bad model."""


def fatal(message: str, force: bool) -> None:
    """Stops, unless --force says to carry on anyway.

    The failures raised this way are ones where continuing is near-certainly pointless. --force exists
    because "near-certainly" is not "certainly", and it is rude to make someone edit the script to
    override a judgement call.
    """
    if force:
        say(f"FAILED   {message}")
        say("         continuing anyway because --force was given")
    else:
        raise Fatal(message)


# --------------------------------------------------------------------------------------------------
# Running things
# --------------------------------------------------------------------------------------------------


def run(command: list[str], cwd: Path, what: str) -> str:
    """Runs a command, streaming its output and returning it.

    Streamed rather than captured-then-printed: these steps take hours, and a run that prints nothing
    until it finishes is indistinguishable from one that has hung.
    """
    say(f"$ {' '.join(str(part) for part in command)}")
    lines: list[str] = []
    process = subprocess.Popen(
        command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
    )
    assert process.stdout is not None
    for line in process.stdout:
        lines.append(line)
        print(line.rstrip(), flush=True)
    code = process.wait()
    output = "".join(lines)
    if code != 0:
        raise Fatal(f"{what} failed with exit code {code}")
    return output


def sbt(task: str, what: str) -> str:
    """Runs one sbt task from the repository root.

    Note the JSON arguments below are never quoted. sbt splits a command on whitespace itself, and
    quotes passed through reach circe verbatim and fail to parse - which looks like a config error and
    is really a shell one.
    """
    return run(["sbt", "-batch", task], cwd=ROOT, what=what)


def python_step(script: str, *args: str, what: str) -> str:
    return run([str(PYTHON), script, *args], cwd=HERE, what=what)


# A single number, with either separator, and *not* swallowing a following one. The distinction
# matters because both appear: the JVM formats with the machine's locale ("62,5%") while the Python
# scripts format with a dot ("mean 28.2, max 44"), and a greedy [\d.,]+ reads the second as "28.2,".
NUMBER = r"(\d+(?:[.,]\d+)?)"


def number(text: str) -> float:
    """Parses a number that may use either a comma or a dot as its decimal separator."""
    return float(text.strip().rstrip(".,").replace(",", "."))


def find(pattern: str, text: str, what: str) -> re.Match:
    match = re.search(pattern, text)
    if match is None:
        raise Fatal(f"could not find {what} in the output; the command's format may have changed")
    return match


# --------------------------------------------------------------------------------------------------
# Reading results
# --------------------------------------------------------------------------------------------------


def score_of(output: str, what: str) -> float:
    """The percentage from a benchmark or arena line, as a fraction."""
    match = find(rf"\({NUMBER}%\)", output, what)
    return number(match.group(1)) / 100.0


def wld_of(output: str) -> tuple[int, int, int]:
    match = find(r"W(\d+) L(\d+) D(\d+)", output, "the win/loss/draw counts")
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


# --------------------------------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------------------------------

# 40 games is the noise floor for this project's battery; differences under about 10 points are not
# real. Quoted in the warnings so nobody reads too much into a close result.
NOISE_FLOOR = 0.10


def check_environment(report: Report, data: Path, force: bool) -> None:
    if not PYTHON.exists():
        raise Fatal(
            f"{PYTHON} is missing - create it with:\n"
            "  python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
        )
    if shutil.which("sbt") is None:
        raise Fatal("sbt is not on the PATH")

    free_gb = shutil.disk_usage(ROOT).free / 1e9
    # A full run writes a bootstrap plus one harvest per generation, a few hundred MB in total. The
    # threshold is generous because running out of disk 15 hours in is an expensive way to find out.
    if free_gb < 5:
        fatal(f"only {free_gb:.1f} GB free; a full run needs a few hundred MB and headroom to spare", force)
    elif free_gb < 20:
        report.warn(f"only {free_gb:.1f} GB free - enough, but not much")


def check_harvest(harvest: Path, report: Report, force: bool, label: str) -> dict:
    """Reads a harvest's manifest and its inspection, and complains about anything odd."""
    manifest = json.loads((harvest / "manifest.json").read_text())
    stats = python_step("dataset.py", str(harvest), what=f"inspecting {label}")

    positions = manifest["sampleCount"]
    if positions < 1000:
        fatal(f"{label} has only {positions} positions; something went wrong upstream", force)

    outcomes = re.search(rf"win {NUMBER}%, draw {NUMBER}%, loss {NUMBER}%", stats)
    if outcomes:
        win, draw, loss = (number(outcomes.group(index)) for index in (1, 2, 3))
        # A network that has learnt one colour's openings and not the other's shows up here first.
        if abs(win - loss) > 15:
            report.warn(
                f"{label}: red wins {win:.0f}% and loses {loss:.0f}% - a gap that large suggests a "
                "colour bias rather than chance"
            )
        # Self-play converging to draws kills the value signal: every position gets the same label.
        if draw > 30:
            report.warn(
                f"{label}: {draw:.0f}% of games were drawn. Above about 30% the value head has little "
                "left to learn from; consider more simulations or a larger network"
            )

    legal = re.search(rf"legal moves\s+min \d+, mean {NUMBER}", stats)
    if legal and number(legal.group(1)) < 5:
        report.warn(f"{label}: only {legal.group(1)} legal moves per position on average - is the board right?")

    return {"manifest": manifest, "stats": stats, "positions": positions}


def fitted_value_scale(stats: str, report: Report) -> float:
    """The scale that turns the evaluator's units into a predicted result.

    Fitted rather than guessed. The units mean nothing on their own, and a first guess at this was once
    wrong by a factor of ten, which saturated two thirds of ordinary positions and left the value head
    regressing on a constant.
    """
    match = re.search(r"suggested --value-scale (\d+)", stats)
    if match is None:
        report.warn("could not read a fitted --value-scale; falling back to 38")
        return 38.0
    return float(match.group(1))


def check_fingerprints(harvest: Path, model: Path, force: bool) -> None:
    """Refuses a model and a harvest that were built against different action orderings.

    They would agree on every shape and disagree on every meaning: the policy head's outputs are
    positions in GameAction.allActions, and a model from before an ordering change points every one of
    them at a different move.
    """
    harvest_print = json.loads((harvest / "manifest.json").read_text())["actionFingerprint"]
    model_print = json.loads((model / "model.json").read_text())["actionFingerprint"]
    if harvest_print != model_print:
        fatal(
            f"action ordering mismatch: the harvest is {harvest_print} and the model is {model_print}. "
            "One of them was built against a different revision; retrain rather than trusting this.",
            force,
        )


def check_sanity(board: str, model: Path, report: Report, force: bool) -> None:
    """The two checks that separate 'weak' from 'broken'.

    A broken network and a merely bad one both lose to the minimax, and the win rate alone cannot tell
    them apart. These can.
    """
    heading("sanity checks")

    beats_random = sbt(
        f'game/run nn-benchmark {model}/model.onnx 400 3 {{"Random":{{}}}} 8 42 16 {board}',
        what="the random-player check",
    )
    random_score = score_of(beats_random, "the score against a random player")
    if random_score < 0.9:
        fatal(
            f"the network scores only {random_score:.0%} against a player that moves at random. "
            "It is not weak, it is broken - check the encoding and the action ordering before going on.",
            force,
        )
    say(f"         beats a random player {random_score:.0%} of the time")

    # The same search with flat priors and no value estimate. This is the bar the network has to clear
    # to be contributing anything at all; on 6x4 it scores 6.3%, on 5x5 it scores 0%.
    uct = sbt(
        f'game/run nn-benchmark uninformed 400 3 {{"Tactical":{{}}}} 8 42 16 {board}',
        what="the flat-prior baseline",
    )
    uct_score = score_of(uct, "the flat-prior baseline")
    report.note(f"flat-prior search alone scores {uct_score:.0%} against depth-3 minimax on this board")
    return uct_score


def choose_benchmark_depth(board: str, report: Report) -> int:
    """Finds which minimax depth is actually strongest on this board.

    Not a formality. On both 6x4 and 5x5 the engine is *stronger at depth 4 than at depth 5* - an
    even-ply search ends on the opponent's reply and cannot be tempted by a capture it will not keep.
    Benchmarking against the weaker setting would flatter every result from here on.
    """
    heading("which minimax depth is the real bar on this board?")
    output = sbt(
        'game/run ai-benchmark 4 {"Tactical":{}} {"Tactical":{}} 10 42 0 5',
        what="the depth-4 against depth-5 comparison",
    )
    depth4_score = score_of(output, "the depth-4 against depth-5 score")
    if depth4_score >= 0.5:
        report.note(f"depth 4 beats depth 5 ({depth4_score:.0%}) - benchmarking against depth 4")
        return 4
    report.note(f"depth 5 beats depth 4 ({1 - depth4_score:.0%}) - benchmarking against depth 5")
    report.warn(
        "depth 5 is the stronger setting here, unlike 6x4 and 5x5 where depth 4 wins. Worth a look: "
        "it means the odd/even effect does not hold on this board."
    )
    return 5


# --------------------------------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------------------------------


def harvest_bootstrap(args, data: Path, report: Report) -> Path:
    harvest = data / "bootstrap"
    if (harvest / "manifest.json").exists():
        say(f"bootstrap harvest already present at {harvest}, skipping")
        return harvest

    heading(f"harvesting {args.bootstrap_games} games from the minimax at depth {args.bootstrap_depth}")
    say("This is the longest single step - expect hours. Depth costs far more than games:")
    say("depth 4 is 5-10x slower than depth 3 for labels only somewhat better.")
    sbt(
        f"game/run harvest-positions {harvest} {args.bootstrap_games} {args.bootstrap_depth} "
        f"42 0.06 65536 {args.board}",
        what="the bootstrap harvest",
    )
    return harvest


def train(harvests: list[Path], out: Path, value_scale: float, report: Report, *, init: Path | None,
          epochs: int, lr: float | None, label: str) -> float:
    """Trains, exports, and returns the held-out top-1."""
    command = [str(path) for path in harvests] + ["--out", str(out), "--epochs", str(epochs),
                                                  "--value-scale", str(value_scale)]
    if init is not None:
        command += ["--init", str(init / "model.pt")]
    if lr is not None:
        command += ["--lr", str(lr)]

    output = python_step("train.py", *command, what=f"training {label}")
    python_step("export_onnx.py", str(out / "model.pt"), what=f"exporting {label}")

    match = re.search(r"Best held-out top-1 agreement with the engine: ([\d.]+)", output)
    top1 = float(match.group(1)) if match else 0.0

    # Train top-1 far above held-out means the network is memorising rather than generalising, which is
    # a data problem and not a capacity one: more games will help, a bigger network will not.
    train_top1 = re.findall(r"train policy [\d.]+ value [\d.]+ top1 ([\d.]+)", output)
    if train_top1 and float(train_top1[-1]) - top1 > 0.35:
        report.warn(
            f"{label}: training top-1 is {float(train_top1[-1]):.2f} against a held-out {top1:.2f}. "
            "That gap is overfitting - harvest more games rather than enlarging the network."
        )
    return top1


def benchmark(board: str, model: Path, depth: int, simulations: int, openings: int, label: str) -> float:
    output = sbt(
        f'game/run nn-benchmark {model}/model.onnx {simulations} {depth} {{"Tactical":{{}}}} '
        f"{openings} 42 16 {board}",
        what=f"benchmarking {label}",
    )
    return score_of(output, "the benchmark score")


def self_play(board: str, champion: Path, into: Path, games: int, simulations: int, seed: int) -> None:
    if (into / "manifest.json").exists():
        say(f"{into} already present, skipping self-play for it")
        return
    sbt(
        f"game/run self-play {champion}/model.onnx {into} {games} {simulations} {seed} 16 65536 {board}",
        what=f"self-play into {into.name}",
    )


def arena(board: str, challenger: Path, champion: Path, simulations: int, report: Report) -> tuple[bool, float]:
    output = sbt(
        f"game/run arena {challenger}/model.onnx {champion}/model.onnx {simulations} 20 42 {board}",
        what="the arena",
    )
    score = score_of(output, "the arena score")
    wins, losses, draws = wld_of(output)
    promoted = "PROMOTE" in output

    if draws > 16:
        report.note(
            f"{draws} of {wins + losses + draws} arena games were drawn - the two networks are "
            "converging on each other"
        )
    return promoted, score


# --------------------------------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--board", required=True, choices=BOARDS)
    parser.add_argument("--bootstrap-games", type=int, default=4800,
                        help="games to harvest from the minimax. The main quality/time dial for the start.")
    parser.add_argument("--bootstrap-depth", type=int, default=3,
                        help="minimax depth behind the bootstrap labels. 3 is the sensible default; "
                             "4 is 5-10x slower for labels only somewhat better.")
    parser.add_argument("--generations", type=int, default=5)
    parser.add_argument("--self-play-games", type=int, default=8000)
    parser.add_argument("--simulations", type=int, default=600,
                        help="search per self-play move. This is what makes the teacher stronger than "
                             "the student it is teaching.")
    parser.add_argument("--benchmark-openings", type=int, default=20,
                        help="openings per benchmark; each is played with both colours, so twice this "
                             "many games. 20 is the noise floor - fewer is faster and less trustworthy.")
    parser.add_argument("--restart", action="store_true", help="delete previous work for this board first")
    parser.add_argument("--force", action="store_true",
                        help="carry on past checks that would otherwise stop the run")
    args = parser.parse_args()

    data = ROOT / "data" / f"nn-{args.board}"
    report = Report()
    started = time.time()

    if args.restart and data.exists():
        say(f"--restart: deleting {data}")
        shutil.rmtree(data)
    data.mkdir(parents=True, exist_ok=True)

    say("=" * 78)
    say(f"Training a network for the {args.board} board")
    say(f"  bootstrap  {args.bootstrap_games} games at minimax depth {args.bootstrap_depth}")
    say(f"  self-play  up to {args.generations} generations of {args.self_play_games} games "
        f"at {args.simulations} simulations")
    say(f"  output     {data}")
    say("=" * 78)

    check_environment(report, data, args.force)

    # ---- the supervised bootstrap ----------------------------------------------------------------
    bootstrap = harvest_bootstrap(args, data, report)
    heading("what came out of the harvest")
    harvest_info = check_harvest(bootstrap, report, args.force, "the bootstrap harvest")
    value_scale = fitted_value_scale(harvest_info["stats"], report)
    say(f"         using --value-scale {value_scale:g}")

    heading("training the bootstrap model")
    model = data / "model"
    if not (model / "model.onnx").exists():
        train([bootstrap], model, value_scale, report, init=None, epochs=40, lr=None, label="the bootstrap model")
    else:
        say(f"{model} already present, skipping")
    check_fingerprints(bootstrap, model, args.force)

    uct_score = check_sanity(args.board, model, report, args.force)
    depth = choose_benchmark_depth(args.board, report)

    heading(f"bootstrap model against the minimax at depth {depth}")
    score = benchmark(args.board, model, depth, 1600, args.benchmark_openings // 2, "the bootstrap model")
    say(f"         {score:.1%}")
    if score <= uct_score:
        report.warn(
            f"the network scores {score:.0%} where flat-prior search alone scores {uct_score:.0%}. "
            "It is contributing nothing yet - harvest more games before spending a day on self-play."
        )

    # ---- self-play ------------------------------------------------------------------------------
    champion = model
    previous: Path | None = None
    rejections = 0

    for generation in range(1, args.generations + 1):
        heading(f"generation {generation} of at most {args.generations}")
        harvest = data / f"gen{generation}"
        self_play(args.board, champion, harvest, args.self_play_games, args.simulations, generation * 1000)
        info = check_harvest(harvest, report, args.force, f"generation {generation}")

        # The teacher has to be ahead of the student, or the generation has nothing new to teach.
        teacher = re.search(rf"search value predicts the winner \(decisive positions\): {NUMBER}", info["stats"])
        if teacher:
            say(f"         the search predicts the winner {number(teacher.group(1)):.3f} of the time")

        # Generation 1 still trains on the bootstrap: the network has not yet surpassed the engine that
        # produced it. After that the bootstrap's depth-3 labels would hold it back.
        window = [bootstrap, harvest] if generation == 1 else [p for p in (previous, harvest) if p]
        candidate = data / f"gen{generation}-model"
        if not (candidate / "model.onnx").exists():
            train(window, candidate, value_scale, report, init=champion, epochs=20, lr=8e-4,
                  label=f"generation {generation}")
        else:
            say(f"{candidate} already present, skipping training")

        promoted, arena_score = arena(args.board, candidate, champion, args.simulations, report)
        say(f"         arena against the champion: {arena_score:.1%} - {'promoted' if promoted else 'rejected'}")

        entry = {"generation": generation, "arena": arena_score, "promoted": promoted,
                 "positions": info["positions"]}

        if promoted:
            champion, previous, rejections = candidate, harvest, 0
            entry["benchmark"] = benchmark(args.board, champion, depth, 3200, args.benchmark_openings,
                                           f"generation {generation}")
            say(f"         against depth {depth}: {entry['benchmark']:.1%}")
            if entry["benchmark"] >= 0.975:
                report.note(
                    f"the depth-{depth} benchmark is saturated at {entry['benchmark']:.0%}; from here only "
                    "the arena can tell generations apart"
                )
            if arena_score < 0.5 + NOISE_FLOOR:
                report.note(
                    f"generation {generation} cleared the gate at {arena_score:.0%}, which is within noise "
                    "of a coin flip - the loop is close to converged"
                )
        else:
            rejections += 1
            report.note(f"generation {generation} was rejected; the champion stays put")
            if rejections >= 2:
                report.note("two rejections in a row - stopping, the loop has converged")
                report.generations.append(entry)
                break

        report.generations.append(entry)

    # ---- what happened ---------------------------------------------------------------------------
    heading("summary")
    say(f"board      {args.board}")
    say(f"champion   {champion}")
    say(f"took       {(time.time() - started) / 3600:.1f} hours")
    say()
    say("  gen  positions    arena   vs minimax")
    for entry in report.generations:
        bench = f"{entry['benchmark']:>9.1%}" if "benchmark" in entry else "        -"
        mark = "" if entry["promoted"] else "  (rejected)"
        say(f"  {entry['generation']:>3}  {entry['positions']:>9}  {entry['arena']:>6.1%} {bench}{mark}")

    if report.notes:
        say()
        say("Notes:")
        for note in report.notes:
            say(f"  - {note}")

    if report.warnings:
        say()
        say(f"{len(report.warnings)} warning(s) - worth reading before trusting this model:")
        for warning in report.warnings:
            say(f"  ! {warning}")
    else:
        say()
        say("No warnings.")

    say()
    say("To put this network in the browser, copy it next to the site and add the board to")
    say("NeuralModels in shared-js/src/main/scala/be/doeraene/workers/NeuralModels.scala:")
    say(f"  cp {champion}/model.onnx  frontend/public/nn/mad-{args.board}.onnx")
    say(f"  cp {champion}/model.json  frontend/public/nn/mad-{args.board}.json")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fatal as error:
        say()
        say(f"STOPPED: {error}")
        say("Nothing was thrown away - fix the problem and run again to carry on where this left off,")
        say("or pass --force to push past this particular check.")
        sys.exit(1)
    except KeyboardInterrupt:
        say()
        say("Interrupted. Re-run the same command to carry on from the last completed stage.")
        sys.exit(130)
