"""Evaluation metrics. The headline number is coverage at a fixed precision: in Auto mode
we only act when confident, so what matters is how many skips we can take over while
staying right at least ``min_precision`` of the time."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.metrics import precision_recall_curve, roc_auc_score


@dataclass(frozen=True)
class OperatingPoint:
    threshold: float | None
    coverage: float
    """Share of true skips that would be auto-skipped (recall of SKIP)."""
    precision: float | None


def skip_auc(is_skip: np.ndarray, p_skip: np.ndarray) -> float:
    return float(roc_auc_score(is_skip, p_skip))


def coverage_at_precision(is_skip: np.ndarray, p_skip: np.ndarray, min_precision: float = 0.9) -> OperatingPoint:
    """Highest SKIP recall whose precision is still at least ``min_precision``."""
    precision, recall, thresholds = precision_recall_curve(is_skip, p_skip)
    # The last precision/recall point has no threshold.
    precision, recall = precision[:-1], recall[:-1]
    ok = precision >= min_precision
    if not ok.any():
        return OperatingPoint(threshold=None, coverage=0.0, precision=None)
    best = int(np.argmax(np.where(ok, recall, -1.0)))
    return OperatingPoint(float(thresholds[best]), float(recall[best]), float(precision[best]))



def group_auc(is_skip: np.ndarray, p_skip: np.ndarray, groups: np.ndarray, weighted: bool = False) -> float:
    """Mean of the AUCs within each group that has both classes.

    Per test day, this keeps a fold whose scores sit higher overall from distorting the others,
    which pooling all folds into one AUC would do. Per viewing session, it asks only whether the
    model ranks reels within a sitting, apart from how skip-happy the user was that sitting.
    """
    # Mann-Whitney U per group from within-group ranks (ties share the average rank): the same
    # as skip_auc on each group, but vectorised, since bootstrap_ci calls this thousands of times.
    frame = pd.DataFrame({"y": np.asarray(is_skip, dtype=bool), "p": p_skip, "g": groups})
    frame["rank"] = frame.groupby("g")["p"].rank()
    frame["pos_rank"] = frame["rank"].where(frame["y"], 0.0)
    stats = frame.groupby("g").agg(n=("y", "size"), pos=("y", "sum"), pos_rank=("pos_rank", "sum"))
    stats = stats[(stats["pos"] > 0) & (stats["pos"] < stats["n"])]
    if stats.empty:
        raise ValueError("no group has both classes")
    neg = stats["n"] - stats["pos"]
    auc = (stats["pos_rank"] - stats["pos"] * (stats["pos"] + 1) / 2) / (stats["pos"] * neg)
    return float(np.average(auc, weights=stats["n"] if weighted else None))

def bootstrap_ci(metric, *arrays, strata=None, n: int = 2000, level: float = 0.95, seed: int = 0) -> tuple[float, float]:
    """Percentile bootstrap interval of ``metric(*arrays)``, resampling rows within ``strata`` if given."""
    rng = np.random.default_rng(seed)
    arrays = [np.asarray(a) for a in arrays]
    rows = np.arange(len(arrays[0]))
    groups = [rows] if strata is None else [rows[np.asarray(strata) == s] for s in np.unique(strata)]
    values = []
    for _ in range(n):
        idx = np.concatenate([rng.choice(g, len(g)) for g in groups])
        try:
            values.append(metric(*(a[idx] for a in arrays)))
        except ValueError:  # a resample left a group with one class
            continue
    tail = (1 - level) / 2 * 100
    return float(np.percentile(values, tail)), float(np.percentile(values, 100 - tail))
