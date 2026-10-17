"""Evaluation metrics. The headline number is coverage at a fixed precision: in Auto mode
we only act when confident, so what matters is how many skips we can take over while
staying right at least ``min_precision`` of the time."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
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
