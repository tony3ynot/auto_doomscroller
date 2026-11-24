"""How many days of use the skip model needs.

    python ml/scripts/learning_curve.py data/<export> --out results/<date>-learning-curve [--until YYYY-MM-DD]

The last days of the export are the test period. The model is then given only the k days
before it: it is fitted on those days' reels, and the history features (the creator's skip
share, the recent skip rate) start from nothing at the beginning of those days, as for someone
who installed the app k days before the test period. Writes metrics.json, summary.md,
run.json and learning_curve.pdf/png. Aggregates only.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

import numpy as np

from autodoom.embed import embeddings
from autodoom.experiment import CONTEXT, CREATOR, build, with_embedding
from autodoom.features import decision_features
from autodoom.labels import SKIP
from autodoom.metrics import bootstrap_ci, coverage_at_precision, group_auc
from autodoom.model import skip_model
from autodoom.report import write_run

ENCODER = "mobilenet_v3_small"
WITH_FRAME, WITHOUT_FRAME = "Context + creator + frame head", "Context + creator"


def curve_days(available: int) -> list[int]:
    """Training-window lengths to try: every day up to a week, then every other day, and all of them."""
    return sorted({k for k in [*range(1, 8), *range(9, available, 2), available] if k <= available})


def figure(results: dict, path: Path, test_days: int) -> None:
    import matplotlib.pyplot as plt

    from autodoom.figures import BLUE, COLUMN_WIDTH_IN, GRID, INK, INK_2, MUTED, SURFACE, use_style

    use_style()
    fig, ax = plt.subplots(figsize=(COLUMN_WIDTH_IN, 2.0))
    ax.grid(True, axis="y", color=GRID, linewidth=0.5)
    ax.set_axisbelow(True)
    ax.axhline(0.5, color=INK_2, linewidth=0.6)
    days = [int(k) for k in results]
    ax.annotate("chance", (days[-1], 0.5), xytext=(0, 3), textcoords="offset points", color=INK_2, fontsize=6,
                ha="right")
    for name, color in ((WITHOUT_FRAME, MUTED), (WITH_FRAME, BLUE)):
        auc = [results[str(k)][name]["auc"] for k in days]
        if name == WITH_FRAME:
            low, high = zip(*(results[str(k)][name]["auc_ci95"] for k in days))
            ax.fill_between(days, low, high, color=color, alpha=0.12, linewidth=0)
        ax.plot(days, auc, color=color, linewidth=1.4, marker="o", markersize=3.5, markeredgecolor=SURFACE,
                markeredgewidth=0.6)
        ax.annotate(name, (days[-1], auc[-1]), xytext=(0, 6 if name == WITH_FRAME else -9),
                    textcoords="offset points", color=INK if name == WITH_FRAME else INK_2, fontsize=6.5, ha="right")
    ax.set_xlabel("Days of use before the test period")
    ax.set_ylabel(f"Skip AUC on the last {test_days} days")
    ax.set_xlim(0.5, days[-1] + 0.5)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.tight_layout(pad=0.3)
    for ext in ("pdf", "png"):
        fig.savefig(path.with_suffix(f".{ext}"), dpi=300)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--test-days", type=int, default=7, help="days at the end of the export to test on")
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    parser.add_argument("--components", type=int, default=8, help="principal components of the frame head")
    parser.add_argument("--until", type=date.fromisoformat, help="last day to use (YYYY-MM-DD), e.g. before a test day")
    parser.add_argument("--without", type=date.fromisoformat, nargs="+", default=[],
                        help="days to leave out altogether (YYYY-MM-DD), e.g. ones spent testing the app")
    args = parser.parse_args()

    data = build(args.export, 1, {}, args.until, args.without)
    data, frame = with_embedding(data, "frame", embeddings(args.export, data, ENCODER).to_numpy())
    days = sorted(data["day"].unique())
    test_start = days[-args.test_days]
    base = CONTEXT + CREATOR
    sets = {WITH_FRAME: frame, WITHOUT_FRAME: []}

    results = {}
    for k in curve_days(len(days) - args.test_days):
        window = data[data["day"] >= days[-args.test_days - k]].copy()
        window[base] = decision_features(window)[base]  # the history starts with the window
        rows = window[window["label"].notna() & ~window["revisit"] & window["has_frame"]]
        train, test = rows[rows["day"] < test_start], rows[rows["day"] >= test_start]
        y_train, y = (train["label"] == SKIP).to_numpy(), (test["label"] == SKIP).to_numpy()
        day = test["day"].astype(str).to_numpy()
        results[str(k)] = {"train_reels": len(train), "test_reels": len(test)}
        for name, columns in sets.items():
            model = skip_model(args.c, columns, args.components).fit(train[[*base, *columns]], y_train)
            p = model.predict_proba(test[[*base, *columns]])[:, 1]
            results[str(k)][name] = {
                "auc": group_auc(y, p, day),
                "auc_ci95": bootstrap_ci(group_auc, y, p, day, strata=day),
                "coverage_at_80": float(np.mean([coverage_at_precision(y[day == d], p[day == d], 0.8).coverage
                                                 for d in np.unique(day)])),
            }
        print(f"{k} days: done", flush=True)

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    figure(results, args.out / "learning_curve", args.test_days)
    run = write_run(
        args.out, "ml/scripts/learning_curve.py", args.export, collection=[days[0], days[-1]], reels=len(data),
        test_days=args.test_days, test_period=[test_start, days[-1]], c=args.c, components=args.components, until=args.until,
        without=args.without,
    )

    rows = ["| Days of use | Training reels | " + " | ".join(sets) + " | Coverage @80% |", "|---|---|---|---|---|"]
    for k, r in results.items():
        cells = [f"{r[name]['auc']:.3f} ({r[name]['auc_ci95'][0]:.2f}–{r[name]['auc_ci95'][1]:.2f})" for name in sets]
        rows.append(f"| {k} | {r['train_reels']} | " + " | ".join(cells) + f" | {r[WITH_FRAME]['coverage_at_80']:.0%} |")
    tested = next(iter(results.values()))["test_reels"]
    lines = [
        "# How many days of use the skip model needs",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}. Test period: the last {args.test_days} days",
        f"  ({test_start} – {days[-1]}), {tested} labeled first impressions",
        "- Days of use: the model sees only that many days before the test period. It is fitted on their",
        "  reels, and the history features start from nothing at the beginning of those days",
        "- AUC: mean of the test days' AUCs, 95% bootstrap interval. The test reels are the same in every row",
        f"- Coverage: share of SKIPs caught at 80% precision by {WITH_FRAME.lower()}, with the best",
        "  threshold for each day",
        "",
        *rows,
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
