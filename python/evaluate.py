#!/usr/bin/env python3
"""Measures how good a trained network actually is, on the held-out slice.

Top-1 agreement is the headline number during training because it is cheap and monotone, but on its own
it is misleading: it scores picking a move the engine rated 9.9 when the best was 10.0 exactly as
harshly as picking a blunder. What a search on top of this network actually needs from it is that its
preferred move is nearly as good as the best one, and that the moves it ranks highly contain the best
one. So the number to lead with here is regret - how much evaluator score is given up by playing the
network's favourite move instead of the engine's - and the top-k rates behind it.

    python evaluate.py ../data/nn/bootstrap ../data/nn/model/model.pt
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch

import dataset
from model import MadNet, Shape


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("harvest", type=Path)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--validation-fraction", type=float, default=0.1)
    parser.add_argument("--value-scale", type=float, default=38.0)
    parser.add_argument("--value-blend", type=float, default=0.5)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    harvest = dataset.load(args.harvest)
    _, valid_idx = dataset.split(harvest, args.validation_fraction)

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    shape = Shape(**checkpoint["shape"])
    if checkpoint["action_fingerprint"] != harvest.manifest.action_fingerprint:
        raise SystemExit(
            f"model was trained against ordering {checkpoint['action_fingerprint']} but this harvest is "
            f"{harvest.manifest.action_fingerprint} - every policy index means something different"
        )

    device = torch.device(args.device)
    net = MadNet(shape, channels=checkpoint["channels"], blocks=checkpoint["blocks"]).to(device)
    net.load_state_dict(checkpoint["state_dict"])
    net.eval()

    value_target = dataset.value_targets(harvest, args.value_blend, args.value_scale)

    chosen_ranks: list[np.ndarray] = []
    regrets: list[np.ndarray] = []
    predicted_values: list[np.ndarray] = []

    with torch.no_grad():
        for start in range(0, len(valid_idx), args.batch_size):
            batch = valid_idx[start : start + args.batch_size]
            boards = torch.from_numpy(np.ascontiguousarray(harvest.features[batch])).to(device)
            legal = torch.from_numpy(np.ascontiguousarray(harvest.legal[batch])).to(device)

            logits, value = net(boards)
            logits = logits.masked_fill(~legal, float("-inf"))

            scores = harvest.scores[batch]
            legal_np = harvest.legal[batch]
            masked_scores = np.where(legal_np, scores, -np.inf)
            best_score = masked_scores.max(axis=1)

            order = torch.argsort(logits, dim=1, descending=True).cpu().numpy()
            engine_best = masked_scores.argmax(axis=1)
            # Where the engine's favourite move sits in the network's ranking.
            chosen_ranks.append((order == engine_best[:, None]).argmax(axis=1))
            # What the network's favourite move actually costs, in evaluator units.
            picked = order[:, 0]
            regrets.append(best_score - masked_scores[np.arange(len(batch)), picked])
            predicted_values.append(value.cpu().numpy())

    rank = np.concatenate(chosen_ranks)
    regret = np.concatenate(regrets)
    predicted = np.concatenate(predicted_values)
    actual = value_target[valid_idx]
    outcome = harvest.outcome[valid_idx]

    print(f"Held-out: {len(valid_idx)} positions, {harvest.legal[valid_idx].sum(1).mean():.1f} legal moves each")
    print()
    print("Policy")
    for k in (1, 3, 5, 10):
        print(f"  engine's best move in the network's top {k:<2}   {np.mean(rank < k):.3f}")

    # Terminal positions score in the millions, so a mean over everything would be a report on how often
    # a forced win was missed and nothing else.
    ordinary = np.abs(regret) < 1e4
    print(f"  regret, ordinary positions ({ordinary.mean():.0%})     "
          f"mean {regret[ordinary].mean():.2f}, median {np.median(regret[ordinary]):.2f}")
    print(f"  played a move within 1.0 of the best        {np.mean(regret[ordinary] <= 1.0):.3f}")
    print(f"  played a move within 5.0 of the best        {np.mean(regret[ordinary] <= 5.0):.3f}")
    print(f"  threw away a forced win                     {np.mean(~ordinary):.3f}")
    print()
    print("Value")
    print(f"  MSE against the blended target              {np.mean((predicted - actual) ** 2):.4f}")
    print(f"  correlation with the blended target         {np.corrcoef(predicted, actual)[0, 1]:.3f}")
    decisive = outcome != 0
    print(f"  predicts the winner (decisive positions)    "
          f"{np.mean(np.sign(predicted[decisive]) == np.sign(outcome[decisive])):.3f}")


if __name__ == "__main__":
    main()
