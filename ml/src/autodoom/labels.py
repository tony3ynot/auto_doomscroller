"""Label rule shared with the app.

Must stay in sync with ``ReelLabeler.kt``. The app stores the raw behaviour (dwell, playback
position, loop, likes, saves, comments, carousel swipes) next to its label, so the rule and
thresholds can be re-tuned here without collecting new data.

The label answers the question Auto mode faces in the first second and a half: would the
user swipe away on first impression? WATCH means "gave it a chance", not "watched it all".
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
    idle_length_multiple: float = 3.0
    idle_min_dwell_ms: int = 30_000
    idle_unknown_length_dwell_ms: int = 60_000


def _known(value) -> bool:
    return value is not None and not pd.isna(value)


def is_completed(looped, max_position_ms, duration_ms, config: LabelConfig = LabelConfig()) -> bool:
    """Watched to the end: it started over, or the scrubber got close enough to the end."""
    if looped:
        return True
    if not (_known(max_position_ms) and _known(duration_ms)):
        return False
    return duration_ms > 0 and max_position_ms >= config.completion_ratio * duration_ms


def is_idle(dwell_ms, duration_ms, config: LabelConfig = LabelConfig()) -> bool:
    """Stayed far longer than the reel without doing anything: probably not watching."""
    if _known(duration_ms):
        limit = max(config.idle_min_dwell_ms, duration_ms * config.idle_length_multiple)
    else:
        limit = config.idle_unknown_length_dwell_ms
    return dwell_ms > limit


def label_reel(
    dwell_ms: float,
    duration_ms: float | None,
    completed: bool,
    engaged: bool,
    moved_to_next: bool,
    config: LabelConfig = LabelConfig(),
) -> str | None:
    """``WATCH``, ``SKIP``, or ``None`` when the behaviour is too ambiguous to learn from."""
    if engaged:
        return WATCH
    if is_idle(dwell_ms, duration_ms, config):
        return None
    if completed or dwell_ms >= config.watch_min_dwell_ms:
        return WATCH
    if moved_to_next and dwell_ms < config.skip_max_dwell_ms:
        return SKIP
    return None


def relabel(reels: pd.DataFrame, config: LabelConfig = LabelConfig()) -> pd.Series:
    """Re-apply the rule to an export, including ads, revisits and Auto-mode overrides.

    Columns added in later schema versions default to "unknown" for older exports.
    """

    def one(row) -> str | None:
        if getattr(row, "is_ad", False) or row.auto_skipped:
            return None
        if row.undone:
            return WATCH
        duration_ms = getattr(row, "duration_ms", None)
        completed = is_completed(row.looped, getattr(row, "max_position_ms", None), duration_ms, config)
        engaged = bool(
            row.liked
            or getattr(row, "saved", False)
            or getattr(row, "opened_comments", False)
            or (getattr(row, "carousel_swipes", 0) or 0) > 0
        )
        label = label_reel(row.dwell_ms, duration_ms, completed, engaged, row.moved_to_next, config)
        # Scrolling back through reels already seen isn't a judgement on them.
        if label == SKIP and getattr(row, "revisit", False):
            return None
        return label

    # dtype=object keeps None as None instead of turning it into NaN.
    return pd.Series([one(row) for row in reels.itertuples()], index=reels.index, dtype=object)
