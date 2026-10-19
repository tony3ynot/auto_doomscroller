"""Same cases as ReelLabelerTest.kt."""

import pandas as pd

from autodoom.labels import SKIP, WATCH, is_completed, label_reel, relabel


def label(dwell_ms, duration_ms=None, completed=False, engaged=False, moved_to_next=True):
    return label_reel(dwell_ms, duration_ms, completed, engaged, moved_to_next)


def test_quick_swipe_is_skip():
    assert label(1_500) == SKIP


def test_leaving_the_feed_quickly_is_ignored():
    assert label(1_500, moved_to_next=False) is None


def test_completed_is_watch_even_when_short():
    assert label(3_000, duration_ms=2_800, completed=True) == WATCH


def test_engagement_is_watch_even_when_quick():
    assert label(1_000, engaged=True) == WATCH


def test_five_seconds_is_watch_whatever_the_length():
    assert label(5_000, duration_ms=120_000) == WATCH


def test_middle_dwell_is_ignored():
    assert label(3_500) is None


def test_short_reel_left_looping_is_idle():
    assert label(40_000, duration_ms=6_000, completed=True) is None


def test_long_reel_watched_for_a_while_is_not_idle():
    assert label(100_000, duration_ms=145_000) == WATCH


def test_image_stared_at_for_minutes_is_idle():
    assert label(90_000) is None


def test_engagement_overrides_idle():
    assert label(90_000, engaged=True) == WATCH


def test_completion():
    assert is_completed(True, None, None)
    assert is_completed(False, 32_000, 35_000)
    assert not is_completed(False, 17_000, 35_000)
    assert not is_completed(False, 9_000, None)
    assert not is_completed(False, float("nan"), float("nan"))


BASE = dict(
    dwell_ms=1_000, looped=False, liked=False, saved=False, opened_comments=False, carousel_swipes=0,
    moved_to_next=True, max_position_ms=None, duration_ms=None, is_ad=False, auto_skipped=False,
    undone=False, revisit=False,
)


def test_relabel_applies_ads_and_auto_mode_overrides():
    reels = pd.DataFrame(
        [
            BASE,
            {**BASE, "auto_skipped": True},
            {**BASE, "undone": True},
            {**BASE, "is_ad": True},
        ]
    )
    assert relabel(reels).tolist() == [SKIP, None, WATCH, None]


def test_relabel_counts_carousel_swipes_and_comments_as_engagement():
    reels = pd.DataFrame([{**BASE, "carousel_swipes": 2}, {**BASE, "opened_comments": True}])
    assert relabel(reels).tolist() == [WATCH, WATCH]


def test_relabel_never_skips_a_revisit():
    reels = pd.DataFrame([{**BASE, "dwell_ms": 600, "revisit": True}, {**BASE, "dwell_ms": 600, "revisit": True, "liked": True}])
    assert relabel(reels).tolist() == [None, WATCH]


def test_relabel_reads_old_exports_without_new_columns():
    reels = pd.DataFrame([dict(dwell_ms=1_000, looped=False, liked=False, moved_to_next=True, auto_skipped=False, undone=False)])
    assert relabel(reels).tolist() == [SKIP]
