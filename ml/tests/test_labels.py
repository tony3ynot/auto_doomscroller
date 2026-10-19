"""Same cases as ReelLabelerTest.kt."""

import pandas as pd

from autodoom.labels import SKIP, WATCH, is_completed, label_reel, relabel


def label(dwell_ms, completed=False, liked=False, saved=False, moved_to_next=True):
    return label_reel(dwell_ms, completed, liked, saved, moved_to_next)


def test_quick_swipe_is_skip():
    assert label(1_500) == SKIP


def test_leaving_the_feed_quickly_is_ignored():
    assert label(1_500, moved_to_next=False) is None


def test_completed_is_watch_even_when_short():
    assert label(3_000, completed=True) == WATCH


def test_like_is_watch_even_when_quick():
    assert label(1_000, liked=True) == WATCH


def test_save_is_watch_even_when_quick():
    assert label(1_000, saved=True) == WATCH


def test_long_dwell_is_watch():
    assert label(5_000) == WATCH


def test_middle_dwell_is_ignored():
    assert label(3_500) is None


def test_completion():
    assert is_completed(True, None, None)
    assert is_completed(False, 32_000, 35_000)
    assert not is_completed(False, 17_000, 35_000)
    assert not is_completed(False, 9_000, None)
    assert not is_completed(False, float("nan"), float("nan"))


def test_relabel_applies_ads_and_auto_mode_overrides():
    base = dict(
        dwell_ms=1_000, looped=False, liked=False, saved=False, moved_to_next=True,
        max_position_ms=None, duration_ms=None,
    )
    reels = pd.DataFrame(
        [
            {**base, "is_ad": False, "auto_skipped": False, "undone": False},
            {**base, "is_ad": False, "auto_skipped": True, "undone": False},
            {**base, "is_ad": False, "auto_skipped": False, "undone": True},
            {**base, "is_ad": True, "auto_skipped": False, "undone": False},
        ]
    )
    assert relabel(reels).tolist() == [SKIP, None, WATCH, None]


def test_relabel_never_skips_a_revisit():
    base = dict(
        dwell_ms=600, looped=False, liked=False, saved=False, moved_to_next=True,
        is_ad=False, auto_skipped=False, undone=False,
    )
    reels = pd.DataFrame([{**base, "revisit": False}, {**base, "revisit": True}, {**base, "revisit": True, "liked": True}])
    assert relabel(reels).tolist() == [SKIP, None, WATCH]


def test_relabel_reads_old_exports_without_new_columns():
    reels = pd.DataFrame([dict(dwell_ms=1_000, looped=False, liked=False, moved_to_next=True, auto_skipped=False, undone=False)])
    assert relabel(reels).tolist() == [SKIP]
