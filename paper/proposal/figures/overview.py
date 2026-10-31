"""System overview for the proposal (full text width).

    ml/.venv/bin/python paper/proposal/figures/overview.py
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

from autodoom.figures import BLUE, INK, INK_2, MUTED, SURFACE, use_style

use_style()
OUT = Path(__file__).with_suffix("")
BORDER = "#c3c2b7"

# (stage title, boxes); a box is (bold line, detail line, optimized on the phone)
STAGES = [
    ("Feed", [
        ("Instagram Reels", "incl. reels on the home feed", False),
        ("YouTube Shorts", "long-form videos ignored", False),
    ]),
    ("Sense", [
        ("Accessibility service", "reel, dwell, swipes, likes, comments, ads", False),
        ("Screen capture", "settled reel, cropped, 540 px", False),
        ("Front camera", "face and hand landmarks", False),
    ]),
    ("Learn", [
        ("Labeler", "SKIP < 2 s · WATCH", False),
        ("Encoders", "frame: MobileNetV3 · text: OCR + E5", True),
        ("Taste memory + model", "kNN skip share → logistic regression", False),
    ]),
    ("Act", [
        ("Auto mode", "skip when P(skip) ≥ 90% precision", False),
        ("Hands-free mode", "double wink → next · thumbs-up → like", False),
        ("Taste map, report card", "what you watch and skip", False),
    ]),
]
WIDTHS = [1.25, 1.75, 1.75, 1.75]
GAP = 0.17
BOX_H, BOX_GAP, TOP = 0.36, 0.08, 1.38
HEIGHT = 1.62


def main() -> None:
    width = sum(WIDTHS) + GAP * (len(WIDTHS) - 1)
    fig = plt.figure(figsize=(width, HEIGHT))
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, width)
    ax.set_ylim(0, HEIGHT)
    ax.axis("off")

    x = 0.0
    for (title, boxes), w in zip(STAGES, WIDTHS):
        ax.text(x + 0.04, TOP + 0.20, title, color=INK_2, fontsize=7, fontweight="bold", va="center")
        for i, (head, detail, optimized) in enumerate(boxes):
            y = TOP - (i + 1) * BOX_H - i * BOX_GAP
            ax.add_patch(FancyBboxPatch(
                (x + 0.02, y), w - 0.04, BOX_H, boxstyle="round,pad=0,rounding_size=0.05",
                facecolor=SURFACE, edgecolor=BLUE if optimized else BORDER, linewidth=1.1 if optimized else 0.7,
            ))
            ax.text(x + 0.09, y + BOX_H * 0.66, head, color=INK, fontsize=6.4, fontweight="bold", va="center")
            ax.text(x + 0.09, y + BOX_H * 0.30, detail, color=INK_2, fontsize=5.6, va="center")
        if x > 0:
            mid = TOP - 1.5 * BOX_H - 0.5 * BOX_GAP
            ax.add_patch(FancyArrowPatch(
                (x - GAP + 0.01, mid), (x + 0.01, mid), arrowstyle="-|>", mutation_scale=7,
                color=MUTED, linewidth=0.9,
            ))
        x += w + GAP

    # The Feed column has two boxes; its third slot holds the legend.
    legend_y = TOP - 2.5 * BOX_H - 2 * BOX_GAP
    ax.plot([0.06, 0.22], [legend_y + 0.07, legend_y + 0.07], color=BLUE, linewidth=1.1)
    ax.text(0.28, legend_y + 0.07, "optimized for the phone", color=INK_2, fontsize=5.6, va="center")
    ax.text(0.28, legend_y - 0.06, "(quantization, pruning)", color=INK_2, fontsize=5.6, va="center")
    fig.savefig(OUT.with_suffix(".pdf"))
    plt.close(fig)


if __name__ == "__main__":
    main()
