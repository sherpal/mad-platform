"""The policy/value network.

Small on purpose. The board is 6x4, so a residual tower of 4 blocks at 64 channels is about 370k
parameters and roughly 15 MFLOP per position - which is what makes CPU self-play on 20 cores beat
shipping batches to the GPU, and what lets the exported model run in a browser tab at a few hundred
evaluations a second.

The policy head emits **raw logits over every action of the side to move**, unmasked. Masking has to
happen where the legal moves are known, which is in the engine, not here: softmaxing before the mask
would leak probability onto moves that cannot be played. See `Canonical.legalPolicyMask`.
"""

from __future__ import annotations

from dataclasses import dataclass

import torch
from torch import nn


@dataclass(frozen=True)
class Shape:
    planes: int
    rows: int
    cols: int
    policy_size: int


class ResidualBlock(nn.Module):
    def __init__(self, channels: int) -> None:
        super().__init__()
        self.conv1 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.norm1 = nn.BatchNorm2d(channels)
        self.conv2 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.norm2 = nn.BatchNorm2d(channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = torch.relu(self.norm1(self.conv1(x)))
        y = self.norm2(self.conv2(y))
        return torch.relu(x + y)


class MadNet(nn.Module):
    def __init__(self, shape: Shape, channels: int = 64, blocks: int = 4) -> None:
        super().__init__()
        self.shape = shape
        cells = shape.rows * shape.cols

        self.stem = nn.Sequential(
            nn.Conv2d(shape.planes, channels, 3, padding=1, bias=False),
            nn.BatchNorm2d(channels),
            nn.ReLU(inplace=True),
        )
        self.tower = nn.Sequential(*[ResidualBlock(channels) for _ in range(blocks)])

        self.policy_head = nn.Sequential(
            nn.Conv2d(channels, 16, 1, bias=False),
            nn.BatchNorm2d(16),
            nn.ReLU(inplace=True),
            nn.Flatten(),
            nn.Linear(16 * cells, shape.policy_size),
        )
        self.value_head = nn.Sequential(
            nn.Conv2d(channels, 8, 1, bias=False),
            nn.BatchNorm2d(8),
            nn.ReLU(inplace=True),
            nn.Flatten(),
            nn.Linear(8 * cells, 64),
            nn.ReLU(inplace=True),
            nn.Linear(64, 1),
            nn.Tanh(),
        )

    def forward(self, board: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """Returns (policy logits, value in -1..1). Both from the point of view of the side to move."""
        trunk = self.tower(self.stem(board))
        return self.policy_head(trunk), self.value_head(trunk).squeeze(-1)

    def parameter_count(self) -> int:
        return sum(p.numel() for p in self.parameters())
