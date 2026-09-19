#!/usr/bin/env python3
"""Exports a trained checkpoint to ONNX, for the engine and the browser to run.

The same file is loaded by ONNX Runtime on the JVM (self-play, and playing against the minimax) and by
onnxruntime-web in the worker. Keeping it one file is the point: there is no second implementation of
the network to drift.

    python export_onnx.py ../data/nn/model/model.pt --out ../data/nn/model/mad.onnx
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from model import MadNet, Shape

OPSET = 17


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--check-batch", type=int, default=8)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    out = args.out or args.checkpoint.with_suffix(".onnx")

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    shape = Shape(**checkpoint["shape"])
    net = MadNet(shape, channels=checkpoint["channels"], blocks=checkpoint["blocks"])
    net.load_state_dict(checkpoint["state_dict"])
    # Folds the batch norms into a fixed affine. Leaving the net in training mode would export the
    # batch statistics as live operations, which is both slower and wrong for batch size 1.
    net.eval()

    example = torch.zeros(args.check_batch, shape.planes, shape.rows, shape.cols)

    torch.onnx.export(
        net,
        (example,),
        str(out),
        input_names=["board"],
        output_names=["policy", "value"],
        # Self-play batches leaves by the dozen, the browser evaluates a handful at a time, and a test
        # may well pass one. None of those should need their own export.
        dynamic_axes={"board": {0: "batch"}, "policy": {0: "batch"}, "value": {0: "batch"}},
        opset_version=OPSET,
        do_constant_folding=True,
    )

    metadata = {
        "planes": shape.planes,
        "rows": shape.rows,
        "cols": shape.cols,
        "policySize": shape.policy_size,
        "channels": checkpoint["channels"],
        "blocks": checkpoint["blocks"],
        "actionFingerprint": checkpoint["action_fingerprint"],
        "validationTop1": checkpoint.get("validation_top1"),
        "opset": OPSET,
    }
    out.with_suffix(".json").write_text(json.dumps(metadata, indent=2))

    _verify(out, net, example)
    print(f"Wrote {out} ({out.stat().st_size / 1024:.0f} KB) and {out.with_suffix('.json').name}")


def _verify(out: Path, net: MadNet, example: torch.Tensor) -> None:
    """Runs the exported file and checks it agrees with the network it came from.

    Worth the few seconds: an export that silently differs from the checkpoint would show up as an
    engine that plays worse than its training metrics said, with nothing pointing at the export.
    """
    try:
        import onnxruntime
    except ImportError:
        print("onnxruntime not installed - skipping the round-trip check")
        return

    session = onnxruntime.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    board = np.random.default_rng(0).random(example.shape, dtype=np.float32)
    policy, value = session.run(None, {"board": board})

    with torch.no_grad():
        expected_policy, expected_value = net(torch.from_numpy(board))

    np.testing.assert_allclose(policy, expected_policy.numpy(), rtol=1e-4, atol=1e-4)
    np.testing.assert_allclose(value, expected_value.numpy(), rtol=1e-4, atol=1e-4)
    print(f"Round-trip check passed on a batch of {example.shape[0]}")


if __name__ == "__main__":
    main()
