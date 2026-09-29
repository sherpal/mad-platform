#!/usr/bin/env python3
"""Creates the network a tabula-rasa run starts from: the right shape, and no knowledge at all.

A bootstrapped run gets its first network by imitating the minimax, which also fixes the tensor shapes
as a side effect - they are read off the harvest's manifest. A zero run has no harvest and nothing to
imitate, so the shape has to come from somewhere else. It comes from `sbt game/run nn-descriptor`,
whose output this reads: Scala stays the only place that knows how a position is encoded.

    python random_init.py --descriptor ../data/nn-zero-6x4/descriptor.json --out ../data/nn-zero-6x4/gen0-model

**The two output layers are zeroed, not randomised.** The rest of the network keeps its ordinary random
initialisation, but a random final layer would hand the first generation an arbitrary preference - some
action index favoured for no reason - and self-play would then spend generations reinforcing it before
unlearning it. Zeroed heads make generation 0 exactly a uniform prior with a value of 0 everywhere,
which is the same thing `BatchEvaluator.uninformed` does, so the first self-play games are driven purely
by what the search can prove: the wins and losses it actually reaches. That is the honest starting point,
and gradients still flow through a zeroed layer, so nothing is frozen by it.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
from torch import nn

from model import MadNet, Shape


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--descriptor", type=Path, required=True,
                        help="JSON from `sbt game/run nn-descriptor <board>`")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--channels", type=int, default=64)
    parser.add_argument("--blocks", type=int, default=4)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def zero_the_heads(net: MadNet) -> None:
    """Silences both heads by zeroing their last layer, so the network starts with no opinions."""
    for head in (net.policy_head, net.value_head):
        last = [layer for layer in head if isinstance(layer, nn.Linear)][-1]
        nn.init.zeros_(last.weight)
        nn.init.zeros_(last.bias)


def main() -> None:
    args = parse_args()
    descriptor = json.loads(args.descriptor.read_text())

    torch.manual_seed(args.seed)
    shape = Shape(
        planes=descriptor["planeCount"],
        rows=descriptor["rows"],
        cols=descriptor["cols"],
        policy_size=descriptor["policySize"],
    )
    net = MadNet(shape, channels=args.channels, blocks=args.blocks)
    zero_the_heads(net)
    net.eval()

    # Same keys train.py writes, so `--init` can warm-start from this exactly as it would from any
    # later generation, and export_onnx.py can export it without a special case.
    args.out.mkdir(parents=True, exist_ok=True)
    torch.save(
        {
            "state_dict": net.state_dict(),
            "shape": shape.__dict__,
            "channels": args.channels,
            "blocks": args.blocks,
            "action_fingerprint": descriptor["actionFingerprint"],
            "validation_top1": None,
            "selected_on": "nothing - this network was never trained",
        },
        args.out / "model.pt",
    )

    with torch.no_grad():
        policy, value = net(torch.zeros(2, shape.planes, shape.rows, shape.cols))
    assert policy.abs().max().item() == 0.0, "the policy head was meant to start silent"
    assert value.abs().max().item() == 0.0, "the value head was meant to start silent"

    print(f"{descriptor['gameType']}: {shape.planes} x {shape.rows} x {shape.cols} in, "
          f"{shape.policy_size} actions out, {net.parameter_count():,} parameters")
    print(f"Wrote {args.out / 'model.pt'} - uniform policy, value 0, fingerprint "
          f"{descriptor['actionFingerprint']}")


if __name__ == "__main__":
    main()
