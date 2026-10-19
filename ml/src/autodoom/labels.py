"""Label rule shared with the app.

Must stay in sync with ``ReelLabeler.kt``. The app stores the raw behaviour (dwell, playback
position, loop, like, save) next to its label, so thresholds can be re-tuned here without
collecting new data.
"""

from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

SKIP = "SKIP"
WATCH = "WATCH"


@dataclass(frozen=True)
class LabelConfig:
    skip_max_dwell_ms: int = 2_000
    watch_min_dwell_ms: int = 5_000
    completion_ratio: float = 0.9


def is_completed(
    looped: bool,
    max_position_ms: float | None,
    duration_ms: float | None,
    config: LabelConfig = LabelConfig(),
) -> bool:
    """Watched to the end: it started over, or the scrubber got close enough to the end."""
    if looped:
        return True
    if max_position_ms is None or duration_ms is None or pd.isna(max_position_ms) or pd.isna(duration_ms):
        return False
    return duration_ms > 0 and max_position_ms >= config.completion_ratio * duration_ms


def label_reel(
    dwell_ms: float,
    completed: bool,
    liked: bool,
    saved: bool,
    moved_to_next: bool,
    config: LabelConfig = LabelConfig(),
) -> str | None:
    """``WATCH``, ``SKIP``, or ``None`` when the behaviour is too ambiguous to learn from."""
    if liked or saved or completed or dwell_ms >= config.watch_min_dwell_ms:
        return WATCH
    if moved_to_next and dwell_ms < config.skip_max_dwell_ms:
        return SKIP
    return None


def relabel(reels: pd.DataFrame, config: LabelConfig = LabelConfig()) -> pd.Series:
    """Re-apply the rule to an export, including ads and Auto-mode overrides.

    Columns added in later schema versions default to "unknown" for older exports.
    """

    def one(row) -> str | None:
        if getattr(row, "is_ad", False) or row.auto_skipped:
            return None
        if row.undone:
            return WATCH
        completed = is_completed(
            row.looped,
            getattr(row, "max_position_ms", None),
            getattr(row, "duration_ms", None),
            config,
        )
        label = label_reel(row.dwell_ms, completed, row.liked, getattr(row, "saved", False), row.moved_to_next, config)
        # Scrolling back through reels already seen isn't a judgement on them.
        if label == SKIP and getattr(row, "revisit", False):
            return None
        return label

    # dtype=object keeps None as None instead of turning it into NaN.
    return pd.Series([one(row) for row in reels.itertuples()], index=reels.index, dtype=object)
