"""Baselines the learned model has to beat."""

from __future__ import annotations

import numpy as np
import pandas as pd

from .labels import SKIP


def majority_scores(train: pd.DataFrame, test: pd.DataFrame) -> np.ndarray:
    """Same P(skip) for every reel: the training skip rate."""
    labeled = train[train["label"].notna()]
    return np.full(len(test), (labeled["label"] == SKIP).mean())


def creator_history_scores(train: pd.DataFrame, test: pd.DataFrame, prior_strength: float = 5.0) -> np.ndarray:
    """P(skip) = this creator's skip rate in train, smoothed toward the global rate."""
    labeled = train[train["label"].notna()]
    global_rate = (labeled["label"] == SKIP).mean()
    stats = labeled.groupby("creator")["label"].agg(
        skips=lambda s: (s == SKIP).sum(),
        n="size",
    )
    rate = (stats["skips"] + prior_strength * global_rate) / (stats["n"] + prior_strength)
    return test["creator"].map(rate).fillna(global_rate).to_numpy(dtype=float)
