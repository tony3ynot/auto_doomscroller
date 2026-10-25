"""Shared pieces of the skip-model experiments: the dataset, how a feature set is scored, and
how results are tabulated and drawn. The scripts in ``ml/scripts`` pick the feature sets."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from .clean import clean
from .data import load_export
from .embed import embeddings
from .features import decision_features, knn_skip_rate, viewing_sessions
from .labels import SKIP, relabel
from .metrics import bootstrap_ci, coverage_at_precision, group_auc, skip_auc
from .model import skip_model
from .split import rolling_days


def build(export: Path, k: int, similarity: dict[str, str]) -> pd.DataFrame:
    """All reels in time order with their label, decision-time features, and for each
    ``similarity`` entry (feature name -> embedding model) the kNN skip share."""
    _, reels = load_export(export)
    reels = clean(reels).sort_values("started_at_ms").reset_index(drop=True)
    reels["label"] = relabel(reels)
    features = decision_features(reels)
    labeled = reels["label"].notna().to_numpy()
    is_skip = (reels["label"] == SKIP).to_numpy()
    for name, model in similarity.items():
        emb = embeddings(export, reels, model).to_numpy()
        features[name] = knn_skip_rate(emb, is_skip, labeled, k=k)
        features[f"has_{name}"] = ~np.isnan(emb).any(axis=1)
    features["session"] = viewing_sessions(reels)
    features["has_frame"] = reels["id"].map(lambda i: (export / "thumbnails" / f"{i}_0.jpg").exists())
    features["day"] = pd.to_datetime(reels["started_at_ms"], unit="ms", utc=True).dt.tz_convert("Asia/Seoul").dt.date
    return pd.concat([reels, features], axis=1)


def evaluate(data: pd.DataFrame, features: list[str], fitted: bool, c: float = 1.0) -> dict:
    """Fit on earlier days, score each test day. Headline: mean of the per-day AUCs.

    Unfitted sets have one feature, used directly as the score.
    """
    folds, ys, ps, days, sessions = [], [], [], [], []
    for day, train, test in rolling_days(data):
        y_train, y_test = (train["label"] == SKIP).to_numpy(), (test["label"] == SKIP).to_numpy()
        if not features:
            p = np.full(len(test), y_train.mean())
        elif not fitted:
            p = test[features[0]].fillna(y_train.mean()).to_numpy()
        else:
            p = skip_model(c).fit(train[features], y_train).predict_proba(test[features])[:, 1]
        point = coverage_at_precision(y_test, p, 0.8)
        folds.append({"test_day": str(day), "n": len(test), "skip_share": float(y_test.mean()),
                      "auc": skip_auc(y_test, p) if features else 0.5, "coverage_at_80": point.coverage})
        ys.append(y_test), ps.append(p), days.append(np.full(len(test), len(folds)))
        sessions.append(test["session"].to_numpy())
    y, p, day, session = map(np.concatenate, (ys, ps, days, sessions))
    result = {"features": features, "folds": folds, "auc": float(np.mean([f["auc"] for f in folds])),
              "coverage_at_80": float(np.mean([f["coverage_at_80"] for f in folds]))}
    if features:
        result["auc_ci95"] = bootstrap_ci(group_auc, y, p, day, strata=day)
        result["session_auc"] = group_auc(y, p, session, weighted=True)
        result["session_auc_ci95"] = bootstrap_ci(lambda *a: group_auc(*a, weighted=True), y, p, session,
                                                  strata=day)
    return result


def table(results: dict) -> list[str]:
    """Markdown rows: AUC and within-session AUC with intervals, coverage at 80% precision."""
    lines = ["| Feature set | AUC | Within-session AUC | Coverage @80% |", "|---|---|---|---|"]
    for name, r in results.items():
        cells = []
        for key in ("auc", "session_auc"):
            ci = r.get(f"{key}_ci95")
            cells.append(f"{r[key]:.3f} ({ci[0]:.2f}–{ci[1]:.2f})" if ci else (f"{r[key]:.3f}" if key in r else "–"))
        cov = f"{r['coverage_at_80']:.0%}" if r["features"] else "–"
        lines.append(f"| {name} | {cells[0]} | {cells[1]} | {cov} |")
    return lines


METRIC_NOTES = [
    "- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session",
    "  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals",
    "- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day",
    "  (an upper bound; Auto mode has to pick its threshold in advance)",
]


def auc_figure(results: dict, path: Path, highlight: str) -> None:
    """Mean per-day AUC with its 95% interval for each feature set; ``highlight`` in color.
    Writes ``path`` with .pdf and .png suffixes."""
    import matplotlib.pyplot as plt

    from .figures import BLUE, COLUMN_WIDTH_IN, GRID, INK, INK_2, MUTED, SURFACE, use_style

    use_style()
    names = [n for n, r in results.items() if r["features"]][::-1]
    fig, ax = plt.subplots(figsize=(COLUMN_WIDTH_IN, 0.2 * len(names) + 0.45))
    ax.grid(True, axis="x", color=GRID, linewidth=0.5)
    ax.set_axisbelow(True)
    ax.axvline(0.5, color=INK_2, linewidth=0.6)
    ax.annotate("chance", (0.5, len(names) - 0.5), xytext=(3, 0), textcoords="offset points", color=INK_2,
                fontsize=6, va="bottom")
    for i, name in enumerate(names):
        r = results[name]
        color = BLUE if name == highlight else MUTED
        low, high = r["auc_ci95"]
        ax.plot([low, high], [i, i], color=color, linewidth=1.4, solid_capstyle="round")
        ax.plot([r["auc"]], [i], marker="o", markersize=4.5, color=color, markeredgecolor=SURFACE,
                markeredgewidth=0.8, zorder=3)
        if name == highlight:
            ax.annotate(f"{r['auc']:.2f}", (high, i), xytext=(4, 0), textcoords="offset points", color=INK,
                        fontsize=6.5, va="center")
    ax.set_yticks(range(len(names)))
    ax.set_yticklabels(names)
    for label in ax.get_yticklabels():
        label.set_color(INK if label.get_text() == highlight else INK_2)
    ax.set_xlim(0.38, 0.72)
    ax.set_ylim(-0.6, len(names) - 0.1)
    ax.set_xlabel("Skip AUC, mean of test days (95% CI)")
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(axis="y", length=0)
    fig.tight_layout(pad=0.3)
    for ext in ("pdf", "png"):
        fig.savefig(path.with_suffix(f".{ext}"), dpi=300)
    plt.close(fig)
