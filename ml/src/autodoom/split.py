"""Train/test splits. Always split by time, never randomly: reels from the same day and
creator are correlated, and a random split would overstate accuracy."""

from __future__ import annotations

import pandas as pd


def temporal_split(
    reels: pd.DataFrame,
    test_days: int = 2,
    time_col: str = "started_at_ms",
    tz: str = "Asia/Seoul",
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Use the last ``test_days`` calendar days (local time) as the test set."""
    dates = pd.to_datetime(reels[time_col], unit="ms", utc=True).dt.tz_convert(tz).dt.date
    days = sorted(dates.unique())
    if len(days) <= test_days:
        raise ValueError(f"need more than {test_days} days of data, got {len(days)}")
    is_test = dates >= days[-test_days]
    return reels[~is_test], reels[is_test]
