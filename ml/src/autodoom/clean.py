"""Repair exports recorded before the app paused reels instead of ending them (schema < 8).

Older builds ended a reel whenever the user briefly left the feed (notification shade,
heads-up notification, a quick trip to DMs) and started a new record when they came back to
the same reel. They also flagged any reel by a recently seen creator as a revisit when its
caption was missing. ``clean`` re-applies what the app now does on device, so the raw
signals in older exports stay usable.
"""

from __future__ import annotations

import pandas as pd

RESUME_WINDOW_MS = 30_000


def _known(value) -> bool:
    return value is not None and not pd.isna(value)


def _same_reel(a, b) -> bool:
    """Same app, creator and feed position; captions must not contradict each other."""
    if a.package != b.package or a.creator != b.creator:
        return False
    if _known(a.feed_index) and _known(b.feed_index) and a.feed_index != b.feed_index:
        return False
    return not (_known(a.caption) and _known(b.caption) and a.caption != b.caption)


def merge_splits(reels: pd.DataFrame, resume_window_ms: int = RESUME_WINDOW_MS) -> pd.DataFrame:
    """Join records of one viewing that were cut in two by a short trip away from the feed.

    The merged row keeps the first record's ``id`` (its thumbnails) and lists all source ids
    in ``merged_ids``. Time away is added to ``paused_ms``.
    """
    rows: list[dict] = []
    for row in reels.sort_values("started_at_ms").itertuples(index=False):
        current = row._asdict()
        current.setdefault("paused_ms", 0)
        current["paused_ms"] = current["paused_ms"] if _known(current["paused_ms"]) else 0
        current["merged_ids"] = [current["id"]]
        prev = rows[-1] if rows else None
        if prev is not None:
            gap = current["started_at_ms"] - prev["ended_at_ms"]
            p, c = pd.Series(prev), pd.Series(current)
            if not prev["moved_to_next"] and 0 <= gap <= resume_window_ms and _same_reel(p, c):
                rows[-1] = _merge(prev, current, gap)
                continue
        rows.append(current)
    out = pd.DataFrame(rows)
    out["dwell_ms"] = out["ended_at_ms"] - out["started_at_ms"] - out["paused_ms"]
    return out


def _merge(first: dict, second: dict, gap_ms: int) -> dict:
    merged = dict(first)
    merged["ended_at_ms"] = second["ended_at_ms"]
    merged["paused_ms"] = first["paused_ms"] + gap_ms + second["paused_ms"]
    merged["moved_to_next"] = second["moved_to_next"]
    # Button states as of the end of the viewing.
    for key in ("liked", "saved", "like_count", "comment_count"):
        if key in second and _known(second[key]):
            merged[key] = second[key]
    for key in ("looped", "opened_comments", "is_ad", "paid_promotion", "auto_skipped", "undone"):
        if key in first:
            merged[key] = bool(first[key]) or bool(second.get(key, False))
    for key in ("caption", "audio", "loop_estimate_ms"):
        if key in first and not _known(first[key]):
            merged[key] = second.get(key)
    if "duration_ms" in first and (not _known(first["duration_ms"]) or first.get("duration_source") != "SCRUBBER"):
        if _known(second.get("duration_ms")):
            merged["duration_ms"] = second["duration_ms"]
            merged["duration_source"] = second.get("duration_source")
    if "max_position_ms" in first:
        positions = [v for v in (first["max_position_ms"], second.get("max_position_ms")) if _known(v)]
        merged["max_position_ms"] = max(positions) if positions else None
    for key in ("carousel_swipes", "frame_count"):
        if key in first:
            merged[key] = (first[key] or 0) + (second.get(key) or 0)
    merged["merged_ids"] = first["merged_ids"] + second["merged_ids"]
    return merged


def fix_revisits(reels: pd.DataFrame) -> pd.DataFrame:
    """A revisit is a return to a feed position last seen showing the same creator.

    Without a position, fall back to the same creator with the same non-empty caption.
    """
    out = reels.sort_values("started_at_ms").copy()
    creator_at, recent, flags = {}, [], []
    for row in out.itertuples(index=False):
        if _known(row.feed_index):
            key = (row.package, int(row.feed_index))
            flags.append(creator_at.get(key) == row.creator)
            creator_at[key] = row.creator
        else:
            flags.append(any(
                r.package == row.package and r.creator == row.creator and _known(r.caption) and r.caption == row.caption
                for r in recent[-200:]
            ))
        recent.append(row)
    out["revisit"] = flags
    return out


def clean(reels: pd.DataFrame) -> pd.DataFrame:
    """``merge_splits`` then ``fix_revisits``. Re-run ``labels.relabel`` afterwards."""
    return fix_revisits(merge_splits(reels))
