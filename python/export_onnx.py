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

# 18 rather than 17 because that is what the exporter implements natively; asking for 17 makes it export
# 18 and then down-convert through the ONNX C API, which is churn for nothing. onnxruntime-web has
# supported 18 since well before the version this project pins.
OPSET = 18


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
        # One file, not a .onnx plus a .onnx.data. The exporter splits the weights out by default, which
        # the browser would have to serve as a second asset and register by hand with onnxruntime-web -
        # and which is very easy to lose track of when copying "the model" somewhere. At 1.5 MB there is
        # nothing to gain by splitting.
        external_data=False,
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
    rng = np.random.default_rng(0)

    # Several batch sizes, not just the one the model was traced with. Self-play batches leaves by the
    # dozen, the browser evaluates a handful, and MCTS at a terminal node evaluates one; a batch axis
    # that silently froze at the example's size would only show up as a crash much later.
    for batch in (1, 3, example.shape[0], example.shape[0] * 2):
        board = rng.random((batch, *example.shape[1:]), dtype=np.float32)
        policy, value = session.run(None, {"board": board})

        with torch.no_grad():
            expected_policy, expected_value = net(torch.from_numpy(board))

        np.testing.assert_allclose(policy, expected_policy.numpy(), rtol=1e-4, atol=1e-4)
        np.testing.assert_allclose(value, expected_value.numpy(), rtol=1e-4, atol=1e-4)

    assert not list(out.parent.glob(out.name + ".data")), "weights were written outside the .onnx file"
    print(f"Round-trip check passed at batch sizes 1, 3, {example.shape[0]}, {example.shape[0] * 2}")


if __name__ == "__main__":
    main()
