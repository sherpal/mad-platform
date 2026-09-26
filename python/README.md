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
  self-play                         export_onnx.py  ──.onnx──▶  engine + browser worker
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

**5x5 was trained the same way and behaved almost identically**: 20.0% against depth 4 from the
bootstrap, 97.5% after two generations, 98.8% after five, with the same convergence signature on the
same timescale. Two differences worth knowing. Flat-prior search scores 0% there against depth-3
minimax, where it manages 6.3% on 6x4 - the bigger, more open board gives bare search nothing to work
with, so the network carries more of the strength. And its generation 5 was still improving at 66.3%,
so that board likely had another generation or two of headroom.

Depth 4 beating depth 5 has now replicated on all three boards tried, which makes it a property of the
hand-written engine rather than a quirk of one board.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`torch` pulls its CUDA runtime with it, so expect a couple of GB. Training works on CPU too - the
network is small - but the GPU turns a quarter of an hour into a couple of minutes.

---

# Training a network for a board

One command, start to finish:

```bash
cd python
.venv/bin/python pipeline.py --board 5x5
```

`pipeline.py` harvests positions from the minimax, trains on them, then runs self-play generations
until they stop improving. Boards are `6x4`, `5x5`, `4x6`, `aztec`; each gets its own directory under
`data/nn-<board>/`, because a network's input is 19 x rows x cols and models for different boards are
not interchangeable. Budget 12-24 hours for the defaults.

It is safe to interrupt: completed stages are detected and skipped, so re-running the same command
carries on where it left off. `--restart` throws the board's work away and begins again.

## What it checks, and why

Each of these is here because this project hit it.

| check | |
|---|---|
| beats a random player | **stops the run.** Below 90% the network is not weak, it is broken - a bad model and a broken encoding both lose to the minimax, and the win rate cannot tell them apart |
| action fingerprints match | **stops the run.** A harvest and a model built against different orderings agree on every shape and disagree on every meaning |
| which minimax depth is strongest | measured, not assumed. Depth 4 beats depth 5 on all three boards tried so far, so benchmarking against depth 5 would flatter every later result |
| beats flat-prior search | warns if the network adds nothing over bare MCTS - which saves a day of self-play on a useless prior |
| draw rate above 30% | warns: the value head has little left to learn from |
| win/loss gap above 15 points | warns: suggests a colour bias rather than chance |
| training vs held-out top-1 | warns it is data-bound - harvest more games, do not enlarge the network |
| two rejected generations | stops. The loop has converged and further generations are wasted hours |

Warnings are collected and reprinted in a summary at the end, because a 20-hour run scrolls a long way.
`--force` pushes past a check that would otherwise stop things; it exists because "near-certainly
pointless" is not "certainly", and you should not have to edit the script to overrule it.

## The knobs worth touching

`--bootstrap-games` (default 4800) is the main quality dial for the start, and the single longest step.
More genuinely helps: the bootstrap is data-bound rather than capacity-bound. `--bootstrap-depth`
(default 3) costs far more than games - depth 4 is 5-10x slower for labels only somewhat better.

`--simulations` (default 600) is what makes the teacher stronger than the student it is teaching. If
generations stop improving while the board still feels unsolved, this is the knob, not the game count.

## Shipping the result

Copy the champion next to the site and add the board to `NeuralModels` in
`shared-js/src/main/scala/be/doeraene/workers/NeuralModels.scala` - that single list is what tells both
the browser and the frontend a network exists for a board. The script prints the exact commands when it
finishes.

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
| `pipeline.py --board <board>` | the orchestrator; runs everything below in order, with checks |
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
