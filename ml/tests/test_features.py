import numpy as np
import pandas as pd
import pytest

from autodoom.features import PRIOR_STRENGTH, decision_features, knn_skip_rate, viewing_sessions

MIN = 60_000


def reel(id, creator, start, label, dwell=1_000, **kw):
    base = dict(package="com.instagram.android", caption=None, audio=None, media_type="VIDEO",
                is_ad=False, paid_promotion=False, like_count=None, comment_count=None)
    return {**base, "id": id, "creator": creator, "started_at_ms": start, "ended_at_ms": start + dwell,
            "dwell_ms": dwell, "label": label, **kw}


def test_long_gap_starts_a_new_viewing_session():
    reels = pd.DataFrame([reel(1, "a", 0, None), reel(2, "b", MIN, None), reel(3, "c", 20 * MIN, None)])
    assert viewing_sessions(reels).tolist() == [0, 0, 1]


def test_history_only_uses_earlier_reels_and_ignores_ads():
    reels = pd.DataFrame([
        reel(1, "cat", 0, "SKIP"),
        reel(2, "shop", MIN, None, is_ad=True, dwell=200),
        reel(3, "cat", 2 * MIN, "WATCH"),
        reel(4, "cat", 3 * MIN, None),
    ])
    f = decision_features(reels)
    assert f.log_creator_seen.tolist() == pytest.approx(np.log1p([0, 0, 1, 2]))
    assert np.isnan(f.log_prev_dwell[0])
    # The ad's 0.2 s doesn't count as the previous reel's dwell.
    assert f.log_prev_dwell[2] == pytest.approx(np.log1p(1.0))
    # Before reel 3 the global rate is (1 + 1) / (1 + 2); cat has 1 skip out of 1.
    rate = 2 / 3
    assert f.creator_skip_rate[2] == pytest.approx((1 + PRIOR_STRENGTH * rate) / (1 + PRIOR_STRENGTH))


def test_knn_only_looks_back_at_labeled_reels():
    emb = np.array([[1.0, 0.0], [0.0, 1.0], [0.8, 0.6], [0.0, 1.0]])
    is_skip = np.array([True, False, True, True])
    labeled = np.array([True, True, False, True])
    k1 = knn_skip_rate(emb, is_skip, labeled, k=1)
    assert np.isnan(k1[0])
    assert k1[1] == 1.0  # only reel 0 came before
    assert k1[2] == 1.0  # closer to reel 0 (a skip) than to reel 1
    assert k1[3] == 0.0  # same as reel 1, a watch; reel 3's own label is never used
    # Reel 2 is unlabeled, so the two neighbours of reel 3 are reels 1 and 0.
    assert knn_skip_rate(emb, is_skip, labeled, k=2)[3] == 0.5
