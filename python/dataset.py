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
    pieces_per_team: int
    feature_length: int
    policy_size: int
    action_fingerprint: int
    sample_count: int
    shards: list[dict]
    arrays: dict
    # "SearchScores" (a supervised harvest: raw minimax scores) or "VisitCounts" (self-play: MCTS visit
    # counts and a root value already in -1..1). The two carry the same shapes and need completely
    # different treatment, and nothing downstream could tell them apart by looking at the numbers.
    # Harvests written before this field existed are all SearchScores.
    policy_signal: str = "SearchScores"

    @staticmethod
    def load(root: Path) -> "Manifest":
        raw = json.loads((root / "manifest.json").read_text())
        return Manifest(
            game_type=raw["gameType"],
            rows=raw["rows"],
            cols=raw["cols"],
            plane_count=raw["planeCount"],
            pieces_per_team=raw["piecesPerTeam"],
            feature_length=raw["featureLength"],
            policy_size=raw["policySize"],
            action_fingerprint=raw["actionFingerprint"],
            sample_count=raw["sampleCount"],
            shards=raw["shards"],
            arrays=raw["arrays"],
            policy_signal=raw.get("policySignal", "SearchScores"),
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
    """Turns whatever signal the harvest carries into a distribution over the legal moves.

    Self-play records MCTS visit counts, which are already a distribution once normalised - that is the
    whole point of training on them. Raising them to 1/temperature sharpens or flattens, matching what
    the search itself did when it sampled a move.
    """
    if harvest.manifest.policy_signal == "VisitCounts":
        counts = np.where(harvest.legal, np.maximum(harvest.scores, 0.0), 0.0)
        sharpened = counts ** (1.0 / temperature) if temperature != 1.0 else counts
        totals = sharpened.sum(axis=1, keepdims=True)
        # A position the search never got to expand has nothing to teach; fall back to uniform over the
        # legal moves rather than dividing by zero.
        uniform = harvest.legal / np.maximum(harvest.legal.sum(axis=1, keepdims=True), 1)
        return np.where(totals > 0, sharpened / np.maximum(totals, 1e-12), uniform).astype(np.float32)

    return _softmax_scores(harvest, temperature)


def _softmax_scores(harvest: Harvest, temperature: float) -> np.ndarray:
    """A supervised harvest's raw minimax scores, softmaxed over the legal moves.

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

    Self-play's root value is already an expected outcome in -1..1, so it needs neither the scale nor the
    tanh. Squashing it a second time would flatten every opinion the search had.
    """
    if harvest.manifest.policy_signal == "VisitCounts":
        searched = np.clip(harvest.root_score, -1.0, 1.0)
    else:
        searched = np.tanh(harvest.root_score / scale)
    return ((1.0 - blend) * harvest.outcome + blend * searched).astype(np.float32)


def calibrate_value_scale(harvest: Harvest, terminal_threshold: float = 1e4) -> tuple[float, np.ndarray]:
    """Finds the `value_scale` that best turns the search's score into a predicted result.

    The scale is not a free knob to guess at: it converts evaluator units into "how likely is this to be
    won", and it depends on the evaluator, the search depth and the harvest. Getting it wrong by an order
    of magnitude - which is easy, the units are arbitrary - either saturates every ordinary position to
    +-1 or squashes a decisive one to nothing, and in both cases the value head learns very little.

    Fitted by scanning for the scale that minimises squared error between `tanh(score / scale)` and the
    result the game actually reached. Terminal scores are excluded: they are +-3e6 and saturate at any
    scale, so they say nothing about where the interesting range is.

    Returns the fitted scale and the agreement-by-magnitude table behind it.
    """
    non_terminal = np.abs(harvest.root_score) < terminal_threshold
    scores = harvest.root_score[non_terminal]
    outcomes = harvest.outcome[non_terminal]

    candidates = np.geomspace(0.5, 500.0, 200)
    errors = [np.mean((np.tanh(scores / scale) - outcomes) ** 2) for scale in candidates]
    best = float(candidates[int(np.argmin(errors))])

    decisive = outcomes != 0
    buckets = []
    for low, high in [(0, 5), (5, 15), (15, 40), (40, terminal_threshold)]:
        inside = decisive & (np.abs(scores) >= low) & (np.abs(scores) < high)
        if inside.sum() > 20:
            agreement = np.mean(np.sign(outcomes[inside]) == np.sign(scores[inside]))
            buckets.append((low, high, int(inside.sum()), float(agreement)))

    return best, np.array(buckets, dtype=object)


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

    # The mover's planes followed by the opponent's; everything after them is a scalar broadcast over
    # the board rather than a piece.
    occupancy = harvest.features[:, : 2 * m.pieces_per_team].sum(axis=(1, 2, 3))
    print(f"  pieces/board   min {occupancy.min():.0f}, mean {occupancy.mean():.1f}, max {occupancy.max():.0f}")

    targets = policy_targets(harvest, temperature=1.0)
    best_mass = targets.max(axis=1)
    print(f"  policy at T=1  mass on best move: mean {best_mass.mean():.2f}, median {np.median(best_mass):.2f}")

    if m.policy_signal == "VisitCounts":
        # Self-play's root value is already an expected outcome, so there is no scale to fit and the
        # bucketing below would just report that every value is between -1 and 1.
        decisive = harvest.outcome != 0
        agreement = np.mean(np.sign(harvest.root_score[decisive]) == np.sign(harvest.outcome[decisive]))
        print(f"\n  search value predicts the winner (decisive positions): {agreement:.3f}")
        print("  no --value-scale needed: this harvest's values are already expected outcomes")
    else:
        scale, buckets = calibrate_value_scale(harvest)
        print("\nHow well the search's score predicts the result (non-terminal, decisive positions):")
        for low, high, count, agreement in buckets:
            print(f"  |score| in [{low:>3.0f}, {high:>5.0f}): n={count:6d}   correct sign {agreement:.3f}")
        print(f"\n  suggested --value-scale {scale:.0f}")


if __name__ == "__main__":
    main()
