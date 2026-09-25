#!/usr/bin/env bash
#
# Everything, from no model at all to a converged one, unattended.
#
#   scripts/full-pipeline.sh [board] [bootstrap-games] [generations] [self-play-games] [simulations]
#
# 1. harvest positions by watching the minimax play itself
# 2. train on them, export, and measure against the minimax
# 3. one self-play generation off that model
# 4. hand the rest to selfplay-loop.sh
#
# Each board gets its own data directory. A network's input is 19 x rows x cols, so models for
# different boards are not interchangeable and must not be mixed in one place.
set -uo pipefail

BOARD=${1:-5x5}
BOOTSTRAP_GAMES=${2:-4800}
GENERATIONS=${3:-4}
SELFPLAY_GAMES=${4:-8000}
SIMS=${5:-600}
BOOTSTRAP_DEPTH=${6:-3}
# Benchmarks are diagnostics, not the deliverable, and they are not cheap: on 6x4 a single 40-game
# depth-5 run took 54 minutes. Kept small here so the day goes into generations instead.
BENCH_OPENINGS=${7:-10}
BENCH_SIMS=${8:-1600}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$ROOT/data/nn-$BOARD"
PYTHON="$ROOT/python/.venv/bin/python"
LOG="$DATA/pipeline.log"

mkdir -p "$DATA"
say() { echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

say "=========================================================================="
say "Full pipeline on $BOARD"
say "  bootstrap : $BOOTSTRAP_GAMES games at minimax depth $BOOTSTRAP_DEPTH"
say "  self-play : $GENERATIONS generations of $SELFPLAY_GAMES games at $SIMS sims"
say "  everything under $DATA"
say "=========================================================================="

# --- 1. the supervised bootstrap ------------------------------------------------------------------
if [ -f "$DATA/bootstrap/manifest.json" ]; then
  say "bootstrap harvest already present, skipping"
else
  say "--- harvesting from the minimax ------------------------------------------"
  if ! (cd "$ROOT" && sbt -batch \
      "game/run harvest-positions $DATA/bootstrap $BOOTSTRAP_GAMES $BOOTSTRAP_DEPTH 42 0.06 65536 $BOARD" \
      2>&1 | grep -E "Wrote|rror" | tee -a "$LOG"); then
    say "harvest failed; stopping"; exit 1
  fi
fi

say ""
say "--- what came out of it --------------------------------------------------"
(cd "$ROOT/python" && $PYTHON dataset.py "$DATA/bootstrap" 2>&1 | tee -a "$LOG")

# The scale that turns the evaluator's arbitrary units into a predicted result. Fitted rather than
# guessed: the units mean nothing on their own and a wrong guess by an order of magnitude saturates
# every ordinary position, leaving the value head regressing on a constant.
VALUE_SCALE=$(cd "$ROOT/python" && $PYTHON dataset.py "$DATA/bootstrap" 2>/dev/null \
  | grep "suggested --value-scale" | grep -oE "[0-9]+$")
VALUE_SCALE=${VALUE_SCALE:-38}
say "fitted --value-scale $VALUE_SCALE"

# --- 2. train the bootstrap model -----------------------------------------------------------------
say ""
say "--- training the bootstrap model -----------------------------------------"
if ! (cd "$ROOT/python" && $PYTHON train.py "$DATA/bootstrap" --out "$DATA/model" \
      --epochs 40 --value-scale "$VALUE_SCALE" 2>&1 | tail -5 | tee -a "$LOG"); then
  say "training failed; stopping"; exit 1
fi
(cd "$ROOT/python" && $PYTHON export_onnx.py "$DATA/model/model.pt" 2>&1 | grep -E "Round-trip|Wrote" | tee -a "$LOG")
(cd "$ROOT/python" && $PYTHON evaluate.py "$DATA/bootstrap" "$DATA/model/model.pt" \
  --value-scale "$VALUE_SCALE" 2>&1 | tee -a "$LOG")

# Two checks that separate "weak" from "broken", which look identical from a win rate alone: the
# network must crush a random player, and it must beat the same search run with flat priors.
say ""
say "--- sanity checks --------------------------------------------------------"
(cd "$ROOT" && sbt -batch "game/run nn-benchmark $DATA/model/model.onnx 400 3 {\"Random\":{}} 8 42 16 $BOARD" \
  2>&1 | grep -E "^Neural-|rror") | tee -a "$LOG"
(cd "$ROOT" && sbt -batch "game/run nn-benchmark uninformed 400 3 {\"Tactical\":{}} 8 42 16 $BOARD" \
  2>&1 | grep -E "^UCT-|rror") | tee -a "$LOG"

say ""
say "--- bootstrap model vs the minimax ---------------------------------------"
for depth in 3 4; do
  (cd "$ROOT" && sbt -batch \
    "game/run nn-benchmark $DATA/model/model.onnx $BENCH_SIMS $depth {\"Tactical\":{}} $BENCH_OPENINGS 42 16 $BOARD" \
    2>&1 | grep -E "^Neural-|rror") | sed "s/^/  depth $depth: /" | tee -a "$LOG"
done

# Which depth is actually the minimax's best is a property of the engine and the board, not a given:
# on 6x4 depth 4 beats depth 5. Worth knowing here too, since it decides what "the bar" means.
say ""
say "--- is the minimax stronger at depth 4 or 5 on this board? ----------------"
(cd "$ROOT" && sbt -batch "game/run ai-benchmark 4 {\"Tactical\":{}} {\"Tactical\":{}} $BENCH_OPENINGS 42 0 5" \
  2>&1 | grep -E "^Tactical|rror") | tee -a "$LOG"

# --- 3. generation 1 ------------------------------------------------------------------------------
say ""
say "--- generation 1: self-play off the bootstrap model ----------------------"
rm -rf "$DATA/gen1"
if ! (cd "$ROOT" && sbt -batch \
    "game/run self-play $DATA/model/model.onnx $DATA/gen1 $SELFPLAY_GAMES $SIMS 1000 16 65536 $BOARD" \
    2>&1 | grep -E "Wrote|rror" | tee -a "$LOG"); then
  say "generation 1 self-play failed; stopping"; exit 1
fi

# Generation 1 is the one place the bootstrap is still worth training on: the network has not yet
# surpassed the engine that produced it. From generation 2 on, selfplay-loop.sh drops it.
if ! (cd "$ROOT/python" && $PYTHON train.py "$DATA/bootstrap" "$DATA/gen1" \
      --init "$DATA/model/model.pt" --out "$DATA/gen1-model" \
      --epochs 20 --lr 8e-4 --value-scale "$VALUE_SCALE" 2>&1 | tail -4 | tee -a "$LOG"); then
  say "generation 1 training failed; stopping"; exit 1
fi
(cd "$ROOT/python" && $PYTHON export_onnx.py "$DATA/gen1-model/model.pt" 2>&1 | grep -E "Round-trip|Wrote" | tee -a "$LOG")

say ""
say "--- generation 1 vs the bootstrap model ----------------------------------"
(cd "$ROOT" && sbt -batch "game/run arena $DATA/gen1-model/model.onnx $DATA/model/model.onnx $SIMS 20 42 $BOARD" \
  2>&1 | grep -E "^challenger|PROMOTE|keep the") | tee -a "$LOG"

# --- 4. the rest of the generations ---------------------------------------------------------------
say ""
say "--- handing over to the self-play loop -----------------------------------"
"$ROOT/scripts/selfplay-loop.sh" "$GENERATIONS" "$SELFPLAY_GAMES" "$SIMS" \
  "$DATA/gen1-model" 2 "$BOARD" "$DATA" 4

say ""
say "=========================================================================="
say "Pipeline finished on $BOARD"
say "=========================================================================="
