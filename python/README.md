# Training the Mad network

Everything here exists to turn harvested positions into a `.onnx` file. It is the only part of the
project written in Python, and it is kept deliberately ignorant: it never encodes a position, never
decides whether a move is legal, and never plays a game. The engine does all of that, in Scala, once,
and the same code is what runs in the browser.

```
  Scala (JVM)                       Python                     Scala (JVM + Wasm)
  harvest-positions   ──shards──▶   train.py                            │
  self-play                         export_onnx.py  ──mad.onnx──▶  engine + worker
```

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`torch` pulls its CUDA runtime with it, so expect a couple of GB. Training works on CPU too - the
network is small - but the GPU turns an hour into minutes.

## The bootstrap, end to end

From the repository root, harvest positions by watching the existing minimax engine play itself:

```bash
sbt "game/run harvest-positions ./data/nn/bootstrap 4000 4"
```

Depth is what a harvest costs. Depth 3 is roughly an order of magnitude cheaper than depth 4 and is the
right place to start, since the point of this stage is to prove the pipeline rather than to produce the
final network. Inspect what came out before training on it:

```bash
cd python && .venv/bin/python dataset.py ../data/nn/bootstrap
```

That inspection ends with a fitted `--value-scale`, and it is worth reading rather than skipping. The
scale converts the evaluator's arbitrary units into a predicted result; the units are not meaningful on
their own, and a first guess at this was wrong by a factor of ten, which quietly saturated every
ordinary position to +-1. Pass what it suggests:

```bash
.venv/bin/python train.py ../data/nn/bootstrap --out ../data/nn/model --value-scale 56
.venv/bin/python export_onnx.py ../data/nn/model/model.pt
```

## What to watch

Top-1 agreement is the number training prints, but it is a harsh one - it scores playing a move the
engine rated 9.9 exactly as badly as a blunder, when the best was 10.0. Use `evaluate.py` for the
picture that matters:

```bash
.venv/bin/python evaluate.py ../data/nn/bootstrap ../data/nn/model/model.pt
```

**Regret** - how much evaluator score the network's favourite move gives up against the engine's - and
the top-k rates are what tell you whether this is a usable prior. A search explores well beyond the
network's first choice, so the best move being in the top 5 matters more than it being first.

Measured on the first depth-3 bootstrap (100k positions):

| | |
|---|---|
| best move in top 1 / 3 / 5 / 10 | 0.40 / 0.64 / 0.77 / 0.92 |
| median regret | 0.86 |
| threw away a forced win | 0.076 |
| value head predicts the winner | 0.640 |
| *the depth-3 search's own root score, same positions* | *0.646* |

The value head is the clear success: one forward pass judges who is winning as well as the search it was
distilled from. The policy head is a usable prior and no more - and the forced-win blunders are exactly
what putting a search on top of it is for.

## Two things that turned out not to be true

**A first guess put top-1 at 50-70%. It is 40%.** A single forward pass reproducing a 3-ply search's
choice is harder than it sounds, and the network is squarely data-bound rather than capacity-bound:
train top-1 reaches 0.94 against a held-out 0.41. More positions is the lever, not a bigger network.

**Selecting the checkpoint on held-out cross-entropy rather than top-1 does not help.** Held-out CE
bottoms out around epoch 13 and then climbs while top-1 keeps rising, which looks like the network
trading calibration for confidence - so `model-calibrated.pt` is saved alongside `model.pt` to test it.
It loses: top-1 0.373 against 0.403, median regret 1.45 against 0.86. The early checkpoint has an
under-trained policy, not a better-calibrated one. Its *value* head is slightly better (correlation
0.575 against 0.551), so the two heads do want different stopping points, but the effect is small.

Run-to-run noise on these numbers is about 0.006, so treat anything under 0.01 as a tie.

## Two things that will silently ruin a run

**The action ordering.** The policy head's outputs are positions in `GameAction.allActions`, and the
input planes are positions in `GamePiece.orderedPieces`. Both are pinned and fingerprinted on the Scala
side; the fingerprint travels in the harvest manifest and into the exported model's metadata. If a
harvest and a model disagree on it, they agree on every shape and disagree on every meaning. Check it
rather than assuming it.

**The policy mask.** `export_onnx.py` writes out raw, unmasked logits on purpose. Masking belongs where
the legal moves are known, which is the engine - see `Canonical.legalPolicyMask`. Softmaxing before the
mask leaks probability onto moves that cannot be played, and the network is never trained to push those
down.
