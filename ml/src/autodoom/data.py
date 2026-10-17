"""Load an export pulled from the phone (format: docs/data-format.md)."""

from __future__ import annotations

from pathlib import Path

import pandas as pd


def load_export(export_dir: str | Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Return ``(sessions, reels)``. ``reels`` gets an extra ``dwell_ms`` column."""
    export_dir = Path(export_dir)
    sessions = pd.read_json(export_dir / "sessions.jsonl", lines=True)
    reels = pd.read_json(export_dir / "reels.jsonl", lines=True)
    if not reels.empty:
        reels["dwell_ms"] = reels["ended_at_ms"] - reels["started_at_ms"]
    return sessions, reels


def thumbnail_paths(export_dir: str | Path, reel_id: int) -> list[Path]:
    """Frames saved for one reel in research mode, in capture order."""
    thumbs = Path(export_dir) / "thumbnails"
    return sorted(thumbs.glob(f"{reel_id}_*.jpg"), key=lambda p: int(p.stem.split("_")[1]))
