"""Features Auto mode can know when it has to decide, about 0.3 s into a reel.

Only what the app reads at once (app, media type, counts, caption, paid promotion) and what
happened on earlier reels count. Anything measured later, such as playback length from the
scrubber or frames after the first, is missing far more often on reels the user skipped
quickly, so a model would learn the missingness instead of the taste.

History features look only at reels that ended before the current one started, so they are
the same whether a reel lands in train or test: the app keeps learning as it runs.
"""

from __future__ import annotations

from collections import deque

import numpy as np
import pandas as pd

from .labels import SKIP

SESSION_GAP_MS = 5 * 60_000
RECENT_REELS = 10
PRIOR_STRENGTH = 5.0
TZ = "Asia/Seoul"
YOUTUBE = "com.google.android.youtube"


def viewing_sessions(reels: pd.DataFrame, gap_ms: int = SESSION_GAP_MS) -> pd.Series:
    """Number each stretch of continuous viewing; a gap longer than ``gap_ms`` starts a new one."""
    order = reels.sort_values("started_at_ms")
    gap = order["started_at_ms"] - order["ended_at_ms"].shift()
    return (gap > gap_ms).cumsum().reindex(reels.index)


def decision_features(reels: pd.DataFrame) -> pd.DataFrame:
    """One row of numeric features per reel (same index). Needs a ``label`` column for history.

    Missing counts and the first reel's previous dwell stay NaN; impute them in the model.
    """
    order = reels.sort_values("started_at_ms")
    session = viewing_sessions(order)
    local = pd.to_datetime(order["started_at_ms"], unit="ms", utc=True).dt.tz_convert(TZ)
    hour = (local.dt.hour + local.dt.minute / 60).to_numpy()

    creators: dict[tuple, list[int]] = {}  # (app, creator) -> [skips, labeled, seen]
    skips_total = labeled_total = 0
    rows = []
    current = None
    for i, row in enumerate(order.itertuples(index=False)):
        if session.iloc[i] != current:
            current, session_start, position = session.iloc[i], row.started_at_ms, 0
            recent: deque[bool] = deque(maxlen=RECENT_REELS)
            prev_dwell = np.nan
        global_rate = (skips_total + 1) / (labeled_total + 2)
        key = (row.package, row.creator)
        skips, labeled, seen = creators.get(key, (0, 0, 0))
        caption = row.caption if isinstance(row.caption, str) else ""
        rows.append({
            "youtube": float(row.package == YOUTUBE),
            "image": float(row.media_type == "IMAGE"),
            "carousel": float(row.media_type == "CAROUSEL"),
            "promotion": float(bool(row.paid_promotion)),
            "log_likes": np.log1p(row.like_count) if pd.notna(row.like_count) else np.nan,
            "log_comments": np.log1p(row.comment_count) if pd.notna(row.comment_count) else np.nan,
            "has_caption": float(bool(caption)),
            "log_caption_len": np.log1p(len(caption)),
            "has_audio": float(isinstance(row.audio, str) and bool(row.audio)),
            "hour_sin": np.sin(2 * np.pi * hour[i] / 24),
            "hour_cos": np.cos(2 * np.pi * hour[i] / 24),
            "log_session_position": np.log1p(position),
            "log_session_minutes": np.log1p((row.started_at_ms - session_start) / 60_000),
            "log_prev_dwell": prev_dwell,
            "recent_skip_rate": (sum(recent) + PRIOR_STRENGTH * global_rate) / (len(recent) + PRIOR_STRENGTH),
            "log_creator_seen": np.log1p(seen),
            "creator_skip_rate": (skips + PRIOR_STRENGTH * global_rate) / (labeled + PRIOR_STRENGTH),
        })

        position += 1
        if row.is_ad:
            continue  # ads say nothing about taste, and auto-skipped ones would look like fast skips
        prev_dwell = np.log1p(row.dwell_ms / 1000)
        is_labeled = isinstance(row.label, str)
        if is_labeled:
            recent.append(row.label == SKIP)
            skips_total += row.label == SKIP
            labeled_total += 1
        creators[key] = [skips + (is_labeled and row.label == SKIP), labeled + is_labeled, seen + 1]
    return pd.DataFrame(rows, index=order.index).reindex(reels.index)


def knn_skip_rate(embeddings: np.ndarray, is_skip: np.ndarray, labeled: np.ndarray, k: int = 20) -> np.ndarray:
    """Skip share among the ``k`` most similar earlier labeled reels (rows in time order).

    ``embeddings`` must be L2-normalised; rows with NaN get NaN. The first reels, with fewer
    than ``k`` labeled predecessors, use all of them; the very first gets NaN.
    """
    out = np.full(len(embeddings), np.nan)
    valid = ~np.isnan(embeddings).any(axis=1)
    memory = np.flatnonzero(labeled & valid)
    for i in np.flatnonzero(valid):
        past = memory[memory < i]
        if len(past) == 0:
            continue
        sims = embeddings[past] @ embeddings[i]
        nearest = past[np.argsort(sims)[-k:]]
        out[i] = is_skip[nearest].mean()
    return out
