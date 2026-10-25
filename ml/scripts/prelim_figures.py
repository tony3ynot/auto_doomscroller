"""Preliminary-results figures and numbers from one export.

    python ml/scripts/prelim_figures.py data/<export> --out results/<date>-prelim

Writes dwell_ecdf.pdf/png, outcomes.pdf/png, summary.md and run.json. Sized for one ACM column.
Only aggregate numbers are written; nothing about individual reels or creators.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from autodoom.baselines import creator_history_scores
from autodoom.clean import clean
from autodoom.data import load_export
from autodoom.figures import AQUA, BLUE, COLUMN_WIDTH_IN, GRID, INK, INK_2, NEUTRAL, ORANGE, SURFACE, use_style
from autodoom.labels import relabel
from autodoom.metrics import skip_auc
from autodoom.report import write_run
from autodoom.split import temporal_split

use_style()
APPS = {"com.instagram.android": "Instagram Reels", "com.google.android.youtube": "YouTube Shorts"}
APP_COLOR = {"Instagram Reels": BLUE, "YouTube Shorts": ORANGE}


def load(export: Path) -> pd.DataFrame:
    _, reels = load_export(export)
    reels = clean(reels)
    reels["label"] = relabel(reels)
    reels["app"] = reels["package"].map(APPS)
    return reels


def dwell_ecdf(reels: pd.DataFrame, out: Path) -> dict[str, float]:
    content = reels[~reels.is_ad & ~reels.auto_skipped]
    fig, ax = plt.subplots(figsize=(COLUMN_WIDTH_IN, 1.9))
    ax.grid(True, which="major", color=GRID, linewidth=0.5)
    ax.set_axisbelow(True)
    shares = {}
    for app in APPS.values():
        dwell = np.sort(content.loc[content.app == app, "dwell_ms"].to_numpy() / 1000)
        dwell = np.clip(dwell, 0.1, None)
        y = np.arange(1, len(dwell) + 1) / len(dwell)
        ax.step(dwell, y, where="post", color=APP_COLOR[app], linewidth=1.4, label=f"{app} (n={len(dwell)})")
        shares[app] = float((dwell < 2).mean())
    for x, text in [(2, "SKIP < 2 s"), (5, "WATCH ≥ 5 s")]:
        ax.axvline(x, color=INK_2, linewidth=0.6)
        ax.text(x * 1.06, 0.04, text, color=INK_2, fontsize=6, rotation=90, va="bottom")
    # Both apps cross 2 s at almost the same share, so one label covers them.
    low, high = min(shares.values()), max(shares.values())
    span = f"{low:.0%}" if round(low, 2) == round(high, 2) else f"{low:.0%}–{high:.0%}"
    for app, share in shares.items():
        ax.plot([2], [share], marker="o", markersize=3.5, color=APP_COLOR[app], markeredgecolor=SURFACE,
                markeredgewidth=0.8, zorder=3)
    ax.annotate(f"{span} leave\nwithin 2 s", (2, (low + high) / 2), xytext=(-8, 0), textcoords="offset points",
                color=INK, fontsize=6.5, ha="right", va="center")
    ax.set_xscale("log")
    ax.set_xlim(0.2, 200)
    ax.set_ylim(0, 1)
    ax.set_xticks([0.3, 1, 2, 5, 10, 30, 100])
    ax.set_xticklabels(["0.3", "1", "2", "5", "10", "30", "100"])
    ax.set_yticks([0, 0.25, 0.5, 0.75, 1])
    ax.set_yticklabels(["0%", "25%", "50%", "75%", "100%"])
    ax.set_xlabel("Time on reel before leaving (s, log scale)")
    ax.set_ylabel("Share of reels")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.legend(loc="upper left", frameon=False, handlelength=1.4)
    fig.tight_layout(pad=0.3)
    for ext in ("pdf", "png"):
        fig.savefig(out / f"dwell_ecdf.{ext}", dpi=300)
    plt.close(fig)
    return shares


def outcomes(reels: pd.DataFrame, out: Path) -> pd.DataFrame:
    def outcome(row) -> str:
        if row.is_ad:
            return "Ad"
        return row.label if isinstance(row.label, str) else "No label"

    order = ["SKIP", "WATCH", "No label", "Ad"]
    colors = {"SKIP": BLUE, "WATCH": ORANGE, "No label": NEUTRAL, "Ad": AQUA}
    table = pd.crosstab(reels.app, reels.apply(outcome, axis=1), normalize="index")[order]
    counts = reels.app.value_counts()

    fig, ax = plt.subplots(figsize=(COLUMN_WIDTH_IN, 1.15))
    apps = list(APPS.values())[::-1]
    for i, app in enumerate(apps):
        left = 0.0
        for key in order:
            width = table.loc[app, key]
            ax.barh(i, width, left=left, height=0.62, color=colors[key], edgecolor=SURFACE, linewidth=1.2)
            if width >= 0.07:
                text_color = INK if key == "No label" else "#ffffff"
                ax.text(left + width / 2, i, f"{width:.0%}", ha="center", va="center", color=text_color, fontsize=6.5)
            left += width
    ax.set_yticks(range(len(apps)))
    ax.set_yticklabels([f"{app}\n(n={counts[app]})" for app in apps], color=INK_2)
    ax.set_xlim(0, 1)
    ax.set_xticks([])
    for side in ("top", "right", "bottom", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(axis="y", length=0)
    handles = [plt.Rectangle((0, 0), 1, 1, color=colors[k]) for k in order]
    ax.legend(handles, order, ncol=4, loc="upper center", bbox_to_anchor=(0.45, 1.32), frameon=False,
              handlelength=1, columnspacing=1.2)
    fig.tight_layout(pad=0.3)
    for ext in ("pdf", "png"):
        fig.savefig(out / f"outcomes.{ext}", dpi=300)
    plt.close(fig)
    return table


def summary(reels: pd.DataFrame, shares: dict[str, float], table: pd.DataFrame, out: Path) -> str:
    content = reels[~reels.is_ad & ~reels.auto_skipped]
    days = pd.to_datetime(reels.started_at_ms, unit="ms", utc=True).dt.tz_convert("Asia/Seoul").dt.date
    train, test = temporal_split(reels, test_days=1)
    labeled = test[test.label.notna()]
    is_skip = (labeled.label == "SKIP").to_numpy()
    auc = skip_auc(is_skip, creator_history_scores(train, labeled))
    seen = labeled.creator.isin(train.creator).mean()
    lines = [
        "# Preliminary results",
        f"- Collection: {days.min()} – {days.max()} ({days.nunique()} days), {len(reels)} reels "
        f"({', '.join(f'{a}: {n}' for a, n in reels.app.value_counts().items())})",
        f"- Ads: {int(reels.is_ad.sum())} ({reels.is_ad.mean():.0%} of reels); auto-skipped: {int(reels.auto_skipped.sum())}",
        f"- Median time on non-ad reels: {content.dwell_ms.median() / 1000:.1f} s; left within 2 s: "
        + ", ".join(f"{a} {s:.0%}" for a, s in shares.items()),
        f"- Labels: SKIP {int((reels.label == 'SKIP').sum())}, WATCH {int((reels.label == 'WATCH').sum())}, "
        f"unlabeled {int(reels.label.isna().sum())}",
        f"- Explicit engagement: likes {int(reels.liked.sum())}, saves {int(reels.saved.sum())}",
        f"- Creator-history baseline (train: first {days.nunique() - 1} days, test: last day, n={len(labeled)}): "
        f"skip AUC {auc:.2f}; {seen:.0%} of test-day creators appeared before",
        "",
        "Outcome shares by app:",
        "",
        "| App | " + " | ".join(table.columns) + " |",
        "|---|" + "---|" * len(table.columns),
        *("| " + app + " | " + " | ".join(f"{v:.0%}" for v in row) + " |" for app, row in table.iterrows()),
    ]
    text = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(text)
    return text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    reels = load(args.export)
    shares = dwell_ecdf(reels, args.out)
    table = outcomes(reels, args.out)
    print(summary(reels, shares, table, args.out))
    write_run(args.out, "ml/scripts/prelim_figures.py", args.export, reels=len(reels))


if __name__ == "__main__":
    main()
