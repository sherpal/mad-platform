"""Reads the shards written by `sbt "game/run harvest-positions ..."`.

The format is deliberately dumb: each array is raw little-endian values, gzipped, with the shapes in
`manifest.json` next to the shards. There is no parser to keep in step across the language boundary -
loading a file is `np.frombuffer(gzip.open(path).read(), dtype)` and a reshape.

Run this module directly to inspect a harvest:

    python dataset.py ../data/nn/bootstrap
"""

from __future__ import annotations

import gzip
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np


@dataclass(frozen=True)
class Manifest:
    game_type: str
    rows: int
    cols: int
    plane_count: int
    feature_length: int
    policy_size: int
    action_fingerprint: int
    sample_count: int
    shards: list[dict]
    arrays: dict

    @staticmethod
    def load(root: Path) -> "Manifest":
        raw = json.loads((root / "manifest.json").read_text())
        return Manifest(
            game_type=raw["gameType"],
            rows=raw["rows"],
            cols=raw["cols"],
            plane_count=raw["planeCount"],
            feature_length=raw["featureLength"],
            policy_size=raw["policySize"],
            action_fingerprint=raw["actionFingerprint"],
            sample_count=raw["sampleCount"],
            shards=raw["shards"],
            arrays=raw["arrays"],
        )


@dataclass(frozen=True)
class Harvest:
    """A whole harvest, in memory.

    Small enough to hold: a few hundred thousand positions is a few hundred MB, and keeping it as one
    array beats streaming when every epoch reshuffles anyway.
    """

    manifest: Manifest
    features: np.ndarray  # (n, planes, rows, cols) float32
    scores: np.ndarray  # (n, policy) float32, meaningful only where legal
    legal: np.ndarray  # (n, policy) bool
    root_score: np.ndarray  # (n,) float32
    outcome: np.ndarray  # (n,) float32 in {-1, 0, 1}

    def __len__(self) -> int:
        return self.features.shape[0]


def _read(path: Path, dtype: str) -> np.ndarray:
    with gzip.open(path, "rb") as handle:
        return np.frombuffer(handle.read(), dtype=dtype)


def load(root: Path) -> Harvest:
    manifest = Manifest.load(root)
    arrays = manifest.arrays

    def read_all(key: str) -> np.ndarray:
        spec = arrays[key]
        parts = [_read(root / shard["name"] / spec["file"], spec["dtype"]) for shard in manifest.shards]
        return np.concatenate(parts) if parts else np.empty(0, dtype=spec["dtype"])

    n = manifest.sample_count
    features = read_all("features").reshape(n, manifest.plane_count, manifest.rows, manifest.cols)
    scores = read_all("scores").reshape(n, manifest.policy_size)
    legal = read_all("legal").reshape(n, manifest.policy_size).astype(bool)
    root_score = read_all("rootScore")
    outcome = read_all("outcome")

    return Harvest(manifest, features, scores, legal, root_score, outcome)


def policy_targets(harvest: Harvest, temperature: float) -> np.ndarray:
    """Turns the search's per-move scores into a distribution over the legal moves.

    The scores are raw minimax values, so they need shifting before they can be exponentiated: a forced
    win scores in the millions and `exp` of that is an overflow, not a probability. Subtracting each
    position's best legal score both fixes that and makes the temperature mean the same thing whatever
    part of the game a position comes from. The floor then keeps a hopeless move at exactly zero rather
    than at a denormal.
    """
    shifted = np.where(harvest.legal, harvest.scores, -np.inf)
    shifted = shifted - np.max(shifted, axis=1, keepdims=True)
    logits = np.clip(shifted / temperature, -60.0, None)
    logits = np.where(harvest.legal, logits, -np.inf)

    weights = np.exp(logits)
    return (weights / weights.sum(axis=1, keepdims=True)).astype(np.float32)


def value_targets(harvest: Harvest, blend: float, scale: float) -> np.ndarray:
    """Blends the game's eventual result with the search's own opinion of the position.

    The result is unbiased but sparse - every position of a game shares one number, including the many
    where the game was still even. The search's root score is dense and is what we are trying to distil
    in the first place, but it inherits the hand-tuned evaluator's biases. `blend` is how much to trust
    the latter; `scale` is how many evaluator units count as decisive.
    """
    searched = np.tanh(harvest.root_score / scale)
    return ((1.0 - blend) * harvest.outcome + blend * searched).astype(np.float32)


def split(harvest: Harvest, validation_fraction: float) -> tuple[np.ndarray, np.ndarray]:
    """Indices for a training/validation split, cut contiguously rather than at random.

    Samples arrive grouped by game, so a contiguous cut keeps whole games on one side or the other. A
    random split would scatter near-identical consecutive positions of the same game across both sides
    and make the held-out loss meaninglessly optimistic - the same trap `TexelTuner` documents.
    """
    n = len(harvest)
    cut = int(n * (1.0 - validation_fraction))
    return np.arange(cut), np.arange(cut, n)


def main() -> None:
    import sys

    root = Path(sys.argv[1] if len(sys.argv) > 1 else "../data/nn/bootstrap")
    harvest = load(root)
    m = harvest.manifest

    print(f"{len(harvest)} positions from {root}")
    print(f"  board          {m.game_type}, {m.plane_count} planes of {m.rows}x{m.cols}")
    print(f"  policy         {m.policy_size} actions, fingerprint {m.action_fingerprint}")
    print(f"  legal moves    min {harvest.legal.sum(1).min()}, "
          f"mean {harvest.legal.sum(1).mean():.1f}, max {harvest.legal.sum(1).max()}")
    print(f"  outcomes       win {np.mean(harvest.outcome > 0):.1%}, "
          f"draw {np.mean(harvest.outcome == 0):.1%}, loss {np.mean(harvest.outcome < 0):.1%}")
    print(f"  root score     min {harvest.root_score.min():.2f}, "
          f"median {np.median(harvest.root_score):.2f}, max {harvest.root_score.max():.2f}")

    occupancy = harvest.features[:, : 2 * 8].sum(axis=(1, 2, 3))
    print(f"  pieces/board   min {occupancy.min():.0f}, mean {occupancy.mean():.1f}, max {occupancy.max():.0f}")

    targets = policy_targets(harvest, temperature=1.0)
    best_mass = targets.max(axis=1)
    print(f"  policy at T=1  mass on best move: mean {best_mass.mean():.2f}, median {np.median(best_mass):.2f}")


if __name__ == "__main__":
    main()
