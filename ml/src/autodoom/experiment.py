"""Shared pieces of the skip-model experiments: the dataset, how a feature set is scored, and
how results are tabulated and drawn. The scripts in ``ml/scripts`` pick the feature sets."""

from __future__ import annotations

from collections.abc import Iterable
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from .clean import clean
from .data import load_export, local_day
from .embed import embeddings
from .features import decision_features, knn_skip_rate, viewing_sessions
from .labels import SKIP, relabel
from .metrics import bootstrap_ci, coverage_at_precision, group_auc, skip_auc
from .model import skip_model
from .split import rolling_days

# Decision-time features that don't depend on the reel's content.
CONTEXT = [
    "youtube", "image", "carousel", "promotion", "log_likes", "log_comments", "has_caption",
    "log_caption_len", "has_audio", "hour_sin", "hour_cos", "log_session_position",
    "log_session_minutes", "log_prev_dwell", "recent_skip_rate",
]
CREATOR = ["log_creator_seen", "creator_skip_rate"]


def build(export: Path, k: int, similarity: dict[str, str], until: date | None = None,
          without: Iterable[date] = ()) -> pd.DataFrame:
    """All reels in time order (up to ``until``, leaving out the days in ``without``) with their
    label, decision-time features, and for each ``similarity`` entry (feature name -> embedding
    model) the kNN skip share."""
    _, reels = load_export(export, until, without)
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
    features["day"] = local_day(reels["started_at_ms"])
    return pd.concat([reels, features], axis=1)


def with_embedding(data: pd.DataFrame, name: str, vectors: np.ndarray) -> tuple[pd.DataFrame, list[str]]:
    """``data`` with ``vectors`` (one row per reel) as columns ``<name>_0``, ...; and their names."""
    columns = [f"{name}_{j}" for j in range(vectors.shape[1])]
    return pd.concat([data, pd.DataFrame(np.asarray(vectors), index=data.index, columns=columns)], axis=1), columns


def fold_scores(data: pd.DataFrame, features: list[str], fitted: bool, c: float = 1.0,
                embedding: list[str] = (), components: int = 8) -> pd.DataFrame:
    """P(skip) for every reel of every test day, from a model fitted on the earlier days.

    One row per reel: ``y`` (is SKIP), ``p``, ``fold``, ``day``, ``session``. Unfitted sets have
    one feature, used directly as the score. ``embedding`` columns enter a fitted model through
    their leading principal components (``skip_model``).
    """
    parts = []
    for fold, (day, train, test) in enumerate(rolling_days(data), start=1):
        y_train = (train["label"] == SKIP).to_numpy()
        columns = [*features, *embedding]
        if not columns:
            p = np.full(len(test), y_train.mean())
        elif not fitted:
            p = test[features[0]].fillna(y_train.mean()).to_numpy()
        else:
            model = skip_model(c, embedding, components)
            p = model.fit(train[columns], y_train).predict_proba(test[columns])[:, 1]
        parts.append(pd.DataFrame({"y": (test["label"] == SKIP).to_numpy(), "p": p, "fold": fold,
                                   "day": str(day), "session": test["session"].to_numpy()}))
    return pd.concat(parts, ignore_index=True)


def summarize(scores: pd.DataFrame, features: list[str]) -> dict:
    """Headline numbers for ``fold_scores``: mean of the per-day AUCs, within-session AUC, coverage."""
    folds = []
    for day, test in scores.groupby("fold", sort=True):
        y, p = test["y"].to_numpy(), test["p"].to_numpy()
        folds.append({"test_day": test["day"].iloc[0], "n": len(test), "skip_share": float(y.mean()),
                      "auc": skip_auc(y, p) if features else 0.5,
                      "coverage_at_80": coverage_at_precision(y, p, 0.8).coverage})
    y, p, day, session = (scores[c].to_numpy() for c in ("y", "p", "fold", "session"))
    result = {"features": features, "folds": folds, "auc": float(np.mean([f["auc"] for f in folds])),
              "coverage_at_80": float(np.mean([f["coverage_at_80"] for f in folds]))}
    if features:
        result["auc_ci95"] = bootstrap_ci(group_auc, y, p, day, strata=day)
        result["session_auc"] = group_auc(y, p, session, weighted=True)
        result["session_auc_ci95"] = bootstrap_ci(lambda *a: group_auc(*a, weighted=True), y, p, session,
                                                  strata=day)
    return result


def evaluate(data: pd.DataFrame, features: list[str], fitted: bool, c: float = 1.0) -> dict:
    """Fit on earlier days, score each test day. Headline: mean of the per-day AUCs."""
    return summarize(fold_scores(data, features, fitted, c), features)


def auc_change(scores: pd.DataFrame, reference: pd.DataFrame, n: int = 2000) -> dict:
    """AUC of ``scores`` minus that of ``reference`` on the same reels, with a paired interval.

    Both models are scored on each resample, so what the reels have in common cancels: the
    interval is far tighter than the two models' own intervals suggest.
    """
    if not (scores["y"].to_numpy() == reference["y"].to_numpy()).all():
        raise ValueError("the two score tables cover different reels")
    y, day = scores["y"].to_numpy(), scores["fold"].to_numpy()
    a, b = scores["p"].to_numpy(), reference["p"].to_numpy()

    def difference(y, a, b, day):
        return group_auc(y, a, day) - group_auc(y, b, day)

    return {"auc_change": difference(y, a, b, day),
            "auc_change_ci95": bootstrap_ci(difference, y, a, b, day, strata=day, n=n)}


def table(results: dict, change: str | None = None) -> list[str]:
    """Markdown rows: AUC and within-session AUC with intervals, coverage at 80% precision.

    ``change`` adds a column of that name with each set's ``auc_change`` (see ``auc_change``).
    """
    head = ["Feature set", "AUC", "Within-session AUC", "Coverage @80%"] + ([change] if change else [])
    lines = ["| " + " | ".join(head) + " |", "|---" * len(head) + "|"]
    for name, r in results.items():
        cells = [name]
        for key in ("auc", "session_auc"):
            ci = r.get(f"{key}_ci95")
            cells.append(f"{r[key]:.3f} ({ci[0]:.2f}–{ci[1]:.2f})" if ci else (f"{r[key]:.3f}" if key in r else "–"))
        cells.append(f"{r['coverage_at_80']:.0%}" if r["features"] else "–")
        if change:
            cells.append(signed_interval(r["auc_change"], r["auc_change_ci95"]) if "auc_change" in r else "–")
        lines.append("| " + " | ".join(cells) + " |")
    return lines


def signed_interval(value: float, ci: tuple[float, float]) -> str:
    def signed(x: float) -> str:
        return f"{round(x, 3) + 0.0:+.3f}".replace("-", "−")  # no "-0.000"

    return f"{signed(value)} ({signed(ci[0])} to {signed(ci[1])})"


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
