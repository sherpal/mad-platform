#!/usr/bin/env python3
"""Trains the MAD network to imitate the minimax engine.

This is the supervised bootstrap, not self-play. The targets come from a harvest of engine-vs-engine
games, so what comes out is at best as good as the depth-N engine that produced it - the point is a
warm start that saves days of self-play, and a way to prove the whole pipeline works against a
baseline whose strength is already known.

    python train.py ../data/nn/bootstrap --out ../data/nn/model
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

import dataset
from model import MadNet, Shape


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("harvest", type=Path, help="directory holding manifest.json and the shards")
    parser.add_argument("--out", type=Path, default=Path("../data/nn/model"))
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--lr", type=float, default=2e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--channels", type=int, default=64)
    parser.add_argument("--blocks", type=int, default=4)
    parser.add_argument("--value-weight", type=float, default=1.0, help="weight of the value loss")
    parser.add_argument("--policy-temperature", type=float, default=1.0,
                        help="softmax temperature applied to the search's move scores")
    parser.add_argument("--value-blend", type=float, default=0.5,
                        help="0 = train the value head on the game result only, 1 = on the search score only")
    parser.add_argument("--value-scale", type=float, default=3.0,
                        help="evaluator units that count as a decisive advantage")
    parser.add_argument("--validation-fraction", type=float, default=0.1)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def masked_cross_entropy(logits: torch.Tensor, targets: torch.Tensor, legal: torch.Tensor) -> torch.Tensor:
    """Cross-entropy against the search's distribution, over the legal moves only.

    Illegal moves are pushed to -inf before the log-softmax rather than simply dropped from the sum: the
    network is never asked to rank them, so leaving them in the normaliser would make the loss depend on
    logits nothing ever trains.
    """
    masked = logits.masked_fill(~legal, float("-inf"))
    return -(targets * torch.log_softmax(masked, dim=1).nan_to_num(neginf=0.0)).sum(dim=1).mean()


def top1_agreement(logits: torch.Tensor, targets: torch.Tensor, legal: torch.Tensor) -> torch.Tensor:
    """How often the network's favourite legal move is the search's favourite move.

    The number to actually watch. Cross-entropy keeps falling on the long tail of near-equivalent quiet
    moves long after the network has stopped learning anything that changes how it plays.
    """
    predicted = logits.masked_fill(~legal, float("-inf")).argmax(dim=1)
    return (predicted == targets.argmax(dim=1)).float().mean()


def main() -> None:
    args = parse_args()
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    harvest = dataset.load(args.harvest)
    manifest = harvest.manifest
    print(f"Loaded {len(harvest)} positions from {args.harvest} ({manifest.game_type})")

    policy = dataset.policy_targets(harvest, args.policy_temperature)
    value = dataset.value_targets(harvest, args.value_blend, args.value_scale)
    train_idx, valid_idx = dataset.split(harvest, args.validation_fraction)
    print(f"  {len(train_idx)} training, {len(valid_idx)} held out (contiguous split, whole games)")

    device = torch.device(args.device)
    features = torch.from_numpy(np.ascontiguousarray(harvest.features))
    legal = torch.from_numpy(np.ascontiguousarray(harvest.legal))
    policy_t = torch.from_numpy(policy)
    value_t = torch.from_numpy(value)

    shape = Shape(manifest.plane_count, manifest.rows, manifest.cols, manifest.policy_size)
    net = MadNet(shape, channels=args.channels, blocks=args.blocks).to(device)
    print(f"  network: {args.blocks} blocks x {args.channels} channels, {net.parameter_count():,} parameters")

    optimiser = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    schedule = torch.optim.lr_scheduler.OneCycleLR(
        optimiser,
        max_lr=args.lr,
        total_steps=args.epochs * max(1, (len(train_idx) + args.batch_size - 1) // args.batch_size),
    )
    value_loss_fn = nn.MSELoss()

    def run_batches(indices: np.ndarray, training: bool) -> dict[str, float]:
        net.train(training)
        order = np.random.permutation(indices) if training else indices
        totals = {"policy": 0.0, "value": 0.0, "top1": 0.0}
        seen = 0

        for start in range(0, len(order), args.batch_size):
            batch = torch.from_numpy(np.ascontiguousarray(order[start : start + args.batch_size]))
            boards = features[batch].to(device, non_blocking=True)
            batch_legal = legal[batch].to(device, non_blocking=True)
            batch_policy = policy_t[batch].to(device, non_blocking=True)
            batch_value = value_t[batch].to(device, non_blocking=True)

            with torch.set_grad_enabled(training):
                logits, predicted_value = net(boards)
                policy_loss = masked_cross_entropy(logits, batch_policy, batch_legal)
                value_loss = value_loss_fn(predicted_value, batch_value)
                loss = policy_loss + args.value_weight * value_loss

            if training:
                optimiser.zero_grad(set_to_none=True)
                loss.backward()
                nn.utils.clip_grad_norm_(net.parameters(), 1.0)
                optimiser.step()
                schedule.step()

            size = len(batch)
            totals["policy"] += policy_loss.item() * size
            totals["value"] += value_loss.item() * size
            totals["top1"] += top1_agreement(logits, batch_policy, batch_legal).item() * size
            seen += size

        return {key: total / max(1, seen) for key, total in totals.items()}

    args.out.mkdir(parents=True, exist_ok=True)
    best_top1 = -1.0

    for epoch in range(1, args.epochs + 1):
        started = time.time()
        train_stats = run_batches(train_idx, training=True)
        with torch.no_grad():
            valid_stats = run_batches(valid_idx, training=False)

        print(
            f"epoch {epoch:3d}/{args.epochs}  "
            f"train policy {train_stats['policy']:.4f} value {train_stats['value']:.4f} "
            f"top1 {train_stats['top1']:.3f}  |  "
            f"valid policy {valid_stats['policy']:.4f} value {valid_stats['value']:.4f} "
            f"top1 {valid_stats['top1']:.3f}  ({time.time() - started:.1f}s)"
        )

        if valid_stats["top1"] > best_top1:
            best_top1 = valid_stats["top1"]
            torch.save(
                {
                    "state_dict": net.state_dict(),
                    "shape": shape.__dict__,
                    "channels": args.channels,
                    "blocks": args.blocks,
                    "action_fingerprint": manifest.action_fingerprint,
                    "validation_top1": best_top1,
                },
                args.out / "model.pt",
            )

    (args.out / "training.json").write_text(
        json.dumps(
            {
                "harvest": str(args.harvest),
                "positions": len(harvest),
                "action_fingerprint": manifest.action_fingerprint,
                "best_validation_top1": best_top1,
                "args": {key: str(value) for key, value in vars(args).items()},
            },
            indent=2,
        )
    )
    print(f"Best held-out top-1 agreement with the engine: {best_top1:.3f}")
    print(f"Saved to {args.out / 'model.pt'}")


if __name__ == "__main__":
    main()
