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

**Held-out top-1 agreement**, not the losses. It is the fraction of positions where the network's
favourite legal move is the engine's favourite move, and it is the only number here that corresponds to
playing strength. Cross-entropy keeps drifting down on the long tail of near-equivalent quiet moves long
after the network has stopped learning anything that changes how it plays.

A bootstrap that reaches somewhere in the 50-70% range is doing its job: the network cannot be expected
to reproduce a 4-ply search from a single forward pass, and it does not need to. What it needs to be is
a prior good enough that a search on top of it starts somewhere sensible.

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
