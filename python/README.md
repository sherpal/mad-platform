# Training the Mad network

An AlphaZero-style engine for Mad: a small convolutional network that judges a position and suggests
moves, with a Monte-Carlo tree search on top of it. It plays in a browser tab, and it beats the
hand-written minimax at every depth.

Everything here exists to turn harvested positions into a `.onnx` file. This is the only part of the
project written in Python, and it is kept deliberately ignorant: it never encodes a position, never
decides whether a move is legal, and never plays a game. Scala does all of that, once, and the same
code is what runs in the browser - so there is no second implementation of the rules, the encoding or
the search that can quietly drift away from the one the network was trained against.

```
  Scala (JVM)                       Python                     Scala (JVM + Wasm)
  harvest-positions   ──shards──▶   train.py                            │
  self-play                         export_onnx.py  ──mad.onnx──▶  engine + browser worker
```

## What came out of it

The network is 4 residual blocks of 64 channels - 379,914 parameters, 1.5 MB as ONNX - on a 19-plane
encoding of the 6x4 board, with a 153-output policy head and a scalar value head.

Scored against the `Tactical` minimax over the standard 20-opening battery (40 games, both colours),
with the network searching 3200 simulations per move:

| | vs depth 3 | vs depth 4 | vs depth 5 |
|---|---|---|---|
| supervised bootstrap | 82.5% | 21.3% | 56.3% |
| after 1 self-play generation | 97.5% | 73.8% | 100% |
| after 2 | - | 97.5% | - |
| after 3, 4, 5 | - | **100%** | - |

**Depth 4, not depth 5, is the minimax's strongest setting** - it beats depth 5 head to head, 62.5%.
That is a property of the hand-written engine and has nothing to do with the network, but it means
depth 4 is the bar worth quoting. At the browser's default 800 simulations the shipped model scores
93.8% against it.

The self-play loop converges after about three generations:

| gen | arena vs parent | W-L-D | avg turns | held-out top-1 |
|---|---|---|---|---|
| 1 | 90% | 36-4-0 | ~42 | 0.417 |
| 2 | 96.3% | 38-1-1 | 43.6 | 0.472 |
| 3 | 85.0% | 30-2-8 | 53.0 | 0.548 |
| 4 | 72.5% | 21-3-16 | 61.5 | 0.614 |
| 5 | 58.8% | 16-9-15 | 59.3 | 0.668 |

Generation 5 is not a real improvement - 58.8% over 40 games is about one standard error above a coin
flip. Four measures agree it is a ceiling rather than a bad run: the margin contracts, draws go from
none to forty per cent, games get half again as long, and the network's agreement with its own search
climbs, which is another way of saying the search has less left to teach it.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`torch` pulls its CUDA runtime with it, so expect a couple of GB. Training works on CPU too - the
network is small - but the GPU turns a quarter of an hour into a couple of minutes.

---

# Reproducing from scratch

Every command below is run from the repository root unless it says otherwise. `data/` is gitignored, so
harvests and checkpoints live there without cluttering the repo.

Budget roughly **3 hours for step 1** and **2.5 hours per self-play generation** on 20 cores and a
laptop GPU. Steps 1-4 give a playable engine; step 5 is what makes it strong.

## 1. Harvest positions from the existing minimax

Self-play from a randomly initialised network works, but it wastes days rediscovering things the
hand-written engine already knows. Watching the minimax play itself gives a warm start, and - more
useful early on - it proves the whole pipeline works against a baseline whose strength is known.

```bash
sbt "game/run harvest-positions ./data/nn/bootstrap 2400 3"
```

Arguments are `<output-dir> <games> [minimax-depth] [seed] [exploration-rate] [samples-per-shard]`.

**This is the knob for "more data at the beginning".** 2400 games gives about 100k positions in three
hours; the rate is roughly **9.4 positions/second at depth 3** on 20 cores, and positions scale linearly
with games. Two things to know before turning it up:

- **Depth costs far more than games.** Depth 4 is 5-10x slower than depth 3 for labels that are only
  somewhat better. Depth 3 is the right setting unless you have a day to spare.
- **The bootstrap was data-bound, not capacity-bound** - train top-1 reached 0.94 against a held-out
  0.41 on 100k positions. So more games genuinely helps here, up to a point. 300-500k positions is a
  reasonable target if you want a stronger start than this project had.

The engine is deterministic, so diversity comes from a random opening of a few plies plus an occasional
random move. The *labels* are always the engine's own scores for the position, never the random move
that got played - labelling a position with a random move would teach the network to play randomly.

## 2. Look at what you harvested

```bash
cd python && .venv/bin/python dataset.py ../data/nn/bootstrap
```

This ends with a fitted `--value-scale`, and it is worth reading rather than skipping. The scale
converts the evaluator's arbitrary units into a predicted result. The units mean nothing on their own,
and a first guess at this was wrong by a factor of ten, which quietly saturated two thirds of ordinary
positions to +-1 and would have left the value head regressing on a constant.

## 3. Train and export

```bash
cd python
.venv/bin/python train.py ../data/nn/bootstrap --out ../data/nn/model --value-scale 38
.venv/bin/python export_onnx.py ../data/nn/model/model.pt
```

Then check it, with `evaluate.py` rather than the training log:

```bash
.venv/bin/python evaluate.py ../data/nn/bootstrap ../data/nn/model/model.pt
```

Top-1 agreement is what training prints, but it scores playing a move the engine rated 9.9 exactly as
badly as a blunder when the best was 10.0. **Regret** - how much evaluator score the network's favourite
move gives up - and the top-k rates are what tell you whether this is a usable prior. Expect something
like top-1/3/5/10 of 0.40/0.64/0.77/0.92 and a median regret under 1.0 from a 100k-position bootstrap.
The value head should predict the winner about as well as the search it was distilled from; that part
works easily. The policy head being mediocre is normal and is what the search is for.

## 4. Check it plays

```bash
sbt 'game/run nn-benchmark ./data/nn/model/model.onnx 800 3 {"Tactical":{}} 20'
```

Note the JSON is **unquoted** - `sbt` passes single quotes through verbatim and circe then fails to
parse them. Arguments are `<model.onnx> [simulations] [minimax-depth] [opponent-json] [openings] [seed]`.

Two sanity checks worth running before trusting anything:

```bash
# Should be close to 100%. If not, something is broken rather than weak.
sbt 'game/run nn-benchmark ./data/nn/model/model.onnx 400 3 {"Random":{}} 10'

# The same search with flat priors and no value estimate. The network has to beat this to be
# contributing anything at all - it scored 6.3% where the bootstrap network scored 20.0%.
sbt 'game/run nn-benchmark uninformed 200 3 {"Tactical":{}} 20'
```

## 5. Self-play

This is the step with no ceiling. The labels come from a search over the network's own judgement, which
is stronger than the network alone, so training on them moves the network toward something its own
search already demonstrated - and then it repeats.

First turn the bootstrap model into generation 1:

```bash
sbt "game/run self-play ./data/nn/model/model.onnx ./data/nn/gen1 8000 600"
cd python && .venv/bin/python train.py ../data/nn/bootstrap ../data/nn/gen1 \
    --init ../data/nn/model/model.pt --out ../data/nn/gen1-model --epochs 20 --lr 8e-4 --value-scale 38
.venv/bin/python export_onnx.py ../data/nn/gen1-model/model.pt
```

Then hand the rest to the loop runner, which does self-play, training, export, the arena and the
benchmark for each generation, and only promotes a challenger that clears the gate:

```bash
scripts/selfplay-loop.sh 4 8000 600
```

Arguments are `[generations] [games] [simulations] [champion-dir] [first-generation]`. It defaults to
continuing from `data/nn/gen1-model` at generation 2; pass the last two to start somewhere else. It is
safe to leave running overnight - a rejected generation costs time and nothing else, and the champion
stays put.

**The number to check before each generation** is the one `dataset.py` prints for a self-play harvest:
*search value predicts the winner*. It was 0.732 for a 600-simulation search against 0.650 for the
network's own value head. The teacher has to be ahead of the student; if that gap closes, the generation
has nothing new to teach and the loop has converged.

---

# Improving on the current model

The loop as run has converged, so simply running more generations of the same thing will not help. In
rough order of expected value:

**Use the left-right mirror.** Free 2x data at no harvesting cost. The board's dynamics are
mirror-symmetric (the vertical mirror is already implemented and tested as `GameState.mirrored`); the
horizontal one needs an action permutation in Scala and a test that `TacticalEvaluator` really is
mirror-symmetric. Openings are not left-right symmetric, so mirrored positions are slightly
off-distribution - correctly labelled, but not positions a real game reaches.

**Make the network bigger.** 380k parameters on a 6x4 board may now be the binding constraint. The
bootstrap was clearly data-bound; by generation 5, with 1.7M self-play positions available, it probably
is not any more. `--channels 96 --blocks 6` roughly triples the parameters and is still small enough for
a browser. This is the change most likely to raise the ceiling rather than approach it faster.

**Search deeper during self-play.** The teacher is the search, and the student has been catching up to
it - top-1 against its own search reached 0.668. Raising `simulations` from 600 puts distance back
between them, at a proportional cost in generation time.

**Measure with a gauntlet, not the parent.** The arena only ever says a network beat its own parent,
which is the network grading its own homework, and the depth-4 benchmark saturates at 100% after
generation 3. Scoring each new generation against a *fixed* panel - generations 1, 2 and 3, say - keeps
the numbers comparable across generations in a way parent-only scores fundamentally cannot.

**Note the noise floor.** 40 games is right at it; differences under about 10 points are not real.
Run-to-run variation on the training metrics is about 0.006, so treat anything under 0.01 as a tie.

---

# The tools

| | |
|---|---|
| `dataset.py <harvest>` | load shards; run directly to inspect a harvest and fit `--value-scale` |
| `train.py <harvest...>` | train; takes several harvests, `--init` warm-starts from a checkpoint |
| `evaluate.py <harvest> <checkpoint>` | regret and top-k on the held-out slice - the honest measure |
| `export_onnx.py <checkpoint>` | write the `.onnx` the engine and the browser both load |

Scala side, all via `sbt "game/run ..."`: `harvest-positions`, `self-play`, `arena`, `nn-benchmark`,
and `ai-benchmark` (which takes a 7th argument to give the opponent a different depth, for comparing
one depth against another).

To ship a model to the browser, copy it over the committed asset. No rebuild: it is a static file the
worker fetches at runtime, not something linked into it.

```bash
cp data/nn/gen5-model/model.onnx frontend/public/nn/mad.onnx
cp data/nn/gen5-model/model.json frontend/public/nn/mad.json
```

Verify the copy by playing it rather than by checking the file size - a truncated or mismatched model
still looks like an ONNX file:

```bash
sbt 'game/run nn-benchmark ./frontend/public/nn/mad.onnx 800 4 {"Tactical":{}} 4'
```

`sbt fastOptWorker` is only needed when the worker's *code* changes; it also copies onnxruntime-web's
wasm out of `node_modules`, so run it once after a fresh `npm ci`.

---

# Things that will silently ruin a run

**The action ordering.** The policy head's outputs are positions in `GameAction.allActions`, and the
input planes are positions in `GamePiece.orderedPieces`. Both are pinned and fingerprinted on the Scala
side; the fingerprint travels in the harvest manifest and into the exported model's metadata, and
`OnnxEvaluator` refuses a model whose fingerprint does not match the build. A mismatched pair agrees on
every shape and disagrees on every meaning.

**The policy mask.** `export_onnx.py` writes raw, unmasked logits on purpose. Masking belongs where the
legal moves are known, which is the engine - see `Canonical.legalPolicyMask`. Softmaxing before the mask
leaks probability onto moves that cannot be played, and the network is never trained to push those down.

**The policy signal.** A supervised harvest and a self-play harvest write identical shapes with
completely different meanings - raw minimax scores wanting a softmax and a fitted tanh, against visit
counts and a value already in -1..1 wanting neither. Shards declare which they are, and `train.py`
computes targets per harvest before concatenating. Nothing downstream could tell them apart by looking
at the numbers.

**The arena, not the training metrics.** Generation 1 moved held-out top-1 from 0.403 to 0.417, which
reads as noise; the arena said it beat its parent 90%. Top-1 against a mixed target is a poor proxy.
Gate on games.
