#!/usr/bin/env bash
#
# Runs the self-play loop unattended, for as many generations as asked.
#
#   scripts/selfplay-loop.sh [generations] [games] [simulations]
#
# Each generation: play games against the current champion, retrain on the last two generations of
# self-play, export, and let the arena decide whether the result is actually an improvement. Only a
# challenger that clears the gate becomes the next champion, so a bad generation costs time and
# nothing else.
#
# Deliberately not `set -e`. A generation that fails should stop the loop with a legible message and
# leave the champion where it was, not kill the script mid-way through and leave a half-written model
# looking like a promotion.
set -uo pipefail

GENERATIONS=${1:-4}
GAMES=${2:-8000}
SIMS=${3:-600}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$ROOT/data/nn"
PYTHON="$ROOT/python/.venv/bin/python"
LOG="$DATA/loop.log"

mkdir -p "$DATA"

say() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

# The generation whose model is currently the best known player. Generation 1 came from the
# supervised bootstrap and is where this picks up.
champion_model="$DATA/gen1-model"
first_new=2
last_new=$((first_new + GENERATIONS - 1))

say "=========================================================================="
say "Self-play loop: generations $first_new..$last_new, $GAMES games at $SIMS sims"
say "Champion to beat: $champion_model"
say "=========================================================================="

for gen in $(seq "$first_new" "$last_new"); do
  data_dir="$DATA/gen$gen"
  model_dir="$DATA/gen$gen-model"
  previous=$((gen - 1))

  say ""
  say "--- generation $gen: self-play -------------------------------------------"
  rm -rf "$data_dir"
  if ! (cd "$ROOT" && sbt -batch "game/run self-play $champion_model/model.onnx $data_dir $GAMES $SIMS $((gen * 1000))" 2>&1 | grep -E "Wrote|rror" | tee -a "$LOG"); then
    say "self-play failed; stopping with $champion_model still champion"
    exit 1
  fi

  # A sliding window of the two most recent self-play generations, and no bootstrap.
  #
  # The bootstrap's labels are depth-3 minimax moves, and the network passed that standard long ago -
  # it beats depth 4 now. Continuing to train on them would be holding it to a worse player's opinion.
  # Two generations rather than one because a single generation is narrow: the network only ever sees
  # positions its current self reaches, and the previous generation's games are the cheapest available
  # source of positions it has stopped choosing.
  window="$data_dir"
  [ -d "$DATA/gen$previous" ] && window="$DATA/gen$previous $window"

  say ""
  say "--- generation $gen: training on $window --------------------------------"
  if ! (cd "$ROOT/python" && $PYTHON train.py $window \
        --init "$champion_model/model.pt" --out "$model_dir" \
        --epochs 20 --lr 8e-4 2>&1 | tail -6 | tee -a "$LOG"); then
    say "training failed; stopping with $champion_model still champion"
    exit 1
  fi

  if ! (cd "$ROOT/python" && $PYTHON export_onnx.py "$model_dir/model.pt" 2>&1 | grep -E "Round-trip|Wrote|rror" | tee -a "$LOG"); then
    say "export failed; stopping with $champion_model still champion"
    exit 1
  fi

  say ""
  say "--- generation $gen: arena vs champion -----------------------------------"
  arena=$(cd "$ROOT" && sbt -batch "game/run arena $model_dir/model.onnx $champion_model/model.onnx $SIMS 20" 2>&1 | grep -E "^challenger|PROMOTE|keep the")
  echo "$arena" | tee -a "$LOG"

  if echo "$arena" | grep -q PROMOTE; then
    champion_model="$model_dir"
    say "generation $gen promoted; champion is now $champion_model"
  else
    say "generation $gen rejected; champion stays $champion_model"
  fi

  # Against the hand-written engine at its strongest setting - the only measurement here that is not
  # the network grading its own homework. See the note on depth 4 in the project notes: it beats
  # depth 5, so this is the bar, not depth 5.
  say ""
  say "--- generation $gen: champion vs minimax depth 4 -------------------------"
  (cd "$ROOT" && sbt -batch "game/run nn-benchmark $champion_model/model.onnx 3200 4 {\"Tactical\":{}} 20" 2>&1 | grep -E "^Neural-|rror") | tee -a "$LOG"
done

say ""
say "=========================================================================="
say "Finished. Champion: $champion_model"
say "=========================================================================="
