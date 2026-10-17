import pandas as pd
import pytest

from autodoom.baselines import creator_history_scores, majority_scores
from autodoom.split import temporal_split

DAY_MS = 24 * 3600 * 1000
# 2026-10-20 12:00 KST
T0 = 1_792_465_200_000


def test_temporal_split_keeps_last_days_for_test():
    reels = pd.DataFrame({"started_at_ms": [T0, T0 + DAY_MS, T0 + 2 * DAY_MS, T0 + 3 * DAY_MS]})
    train, test = temporal_split(reels, test_days=2)
    assert train["started_at_ms"].tolist() == [T0, T0 + DAY_MS]
    assert test["started_at_ms"].tolist() == [T0 + 2 * DAY_MS, T0 + 3 * DAY_MS]


def test_temporal_split_needs_enough_days():
    reels = pd.DataFrame({"started_at_ms": [T0, T0 + 1000]})
    with pytest.raises(ValueError):
        temporal_split(reels, test_days=1)


def test_creator_history_smooths_toward_global_rate():
    train = pd.DataFrame(
        {
            "creator": ["cat", "cat", "cat", "dance", "dance", None],
            "label": ["WATCH", "WATCH", "WATCH", "SKIP", "SKIP", None],
        }
    )
    test = pd.DataFrame({"creator": ["cat", "dance", "new"]})
    scores = creator_history_scores(train, test, prior_strength=1.0)
    global_rate = 2 / 5
    assert scores[0] == pytest.approx((0 + global_rate) / (3 + 1))
    assert scores[1] == pytest.approx((2 + global_rate) / (2 + 1))
    assert scores[2] == pytest.approx(global_rate)
    assert majority_scores(train, test).tolist() == pytest.approx([global_rate] * 3)
