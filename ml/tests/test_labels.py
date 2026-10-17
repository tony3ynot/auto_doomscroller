"""Same cases as ReelLabelerTest.kt."""

import pandas as pd

from autodoom.labels import SKIP, WATCH, label_reel, relabel


def test_quick_swipe_is_skip():
    assert label_reel(1_500, looped=False, liked=False, moved_to_next=True) == SKIP


def test_leaving_the_app_quickly_is_ignored():
    assert label_reel(1_500, looped=False, liked=False, moved_to_next=False) is None


def test_loop_is_watch():
    assert label_reel(9_000, looped=True, liked=False, moved_to_next=True) == WATCH


def test_like_is_watch_even_when_quick():
    assert label_reel(1_000, looped=False, liked=True, moved_to_next=True) == WATCH


def test_long_dwell_is_watch():
    assert label_reel(20_000, looped=False, liked=False, moved_to_next=True) == WATCH


def test_middle_dwell_is_ignored():
    assert label_reel(8_000, looped=False, liked=False, moved_to_next=True) is None


def test_relabel_applies_auto_mode_overrides():
    base = dict(dwell_ms=1_000, looped=False, liked=False, moved_to_next=True)
    reels = pd.DataFrame(
        [
            {**base, "auto_skipped": False, "undone": False},
            {**base, "auto_skipped": True, "undone": False},
            {**base, "auto_skipped": False, "undone": True},
        ]
    )
    assert relabel(reels).tolist() == [SKIP, None, WATCH]
