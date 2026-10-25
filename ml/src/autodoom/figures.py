"""Shared look for paper figures: reference palette (dataviz skill, light mode) and one-column sizing."""

from __future__ import annotations

# Validated categorical order: blue, orange, aqua.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
NEUTRAL = "#c3c2b7"  # "no label": recessive, not a series hue
INK, INK_2, MUTED = "#0b0b0b", "#52514e", "#898781"
GRID, AXIS, SURFACE = "#e1e0d9", "#c3c2b7", "#ffffff"
COLUMN_WIDTH_IN = 3.33

STYLE = {
    "font.family": "sans-serif", "font.size": 7, "axes.labelsize": 7, "xtick.labelsize": 6.5,
    "ytick.labelsize": 6.5, "legend.fontsize": 6.5, "axes.edgecolor": AXIS, "axes.labelcolor": INK_2,
    "xtick.color": MUTED, "ytick.color": MUTED, "axes.linewidth": 0.6, "xtick.major.width": 0.6,
    "ytick.major.width": 0.6, "savefig.facecolor": SURFACE, "figure.facecolor": SURFACE,
}


def use_style() -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update(STYLE)
