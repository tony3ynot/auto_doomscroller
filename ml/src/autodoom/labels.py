"""Label rule shared with the app.

Must stay in sync with ``ReelLabeler.kt``. The app stores the raw behaviour (dwell, loop,
like) next to its label, so thresholds can be re-tuned here without collecting new data.
"""

from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

SKIP = "SKIP"
WATCH = "WATCH"


@dataclass(frozen=True)
class LabelConfig:
    skip_max_dwell_ms: int = 2_000
    watch_min_dwell_ms: int = 20_000


def label_reel(
    dwell_ms: float,
    looped: bool,
    liked: bool,
    moved_to_next: bool,
    config: LabelConfig = LabelConfig(),
) -> str | None:
    """``WATCH``, ``SKIP``, or ``None`` when the behaviour is too ambiguous to learn from."""
    if liked or looped or dwell_ms >= config.watch_min_dwell_ms:
        return WATCH
    if moved_to_next and dwell_ms < config.skip_max_dwell_ms:
        return SKIP
    return None


def relabel(reels: pd.DataFrame, config: LabelConfig = LabelConfig()) -> pd.Series:
    """Re-apply the rule to an export, including the Auto-mode overrides."""

    def one(row) -> str | None:
        if row.auto_skipped:
            return None
        if row.undone:
            return WATCH
        return label_reel(row.dwell_ms, row.looped, row.liked, row.moved_to_next, config)

    # dtype=object keeps None as None instead of turning it into NaN.
    return pd.Series([one(row) for row in reels.itertuples()], index=reels.index, dtype=object)
