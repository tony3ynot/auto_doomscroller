import pandas as pd

from autodoom.clean import clean, fix_revisits, merge_splits

BASE = dict(
    package="com.instagram.android", caption=None, audio=None, moved_to_next=True, paused_ms=0,
    liked=False, saved=False, looped=False, opened_comments=False, is_ad=False, paid_promotion=False,
    auto_skipped=False, undone=False, revisit=False, duration_ms=None, duration_source=None,
    max_position_ms=None, loop_estimate_ms=None, carousel_swipes=0, frame_count=1,
    like_count=None, comment_count=None,
)


def reel(id, creator, start, end, feed_index, **kw):
    return {**BASE, "id": id, "creator": creator, "started_at_ms": start, "ended_at_ms": end,
            "feed_index": feed_index, **kw}


def test_short_trip_away_is_merged_and_not_counted_as_dwell():
    reels = pd.DataFrame([
        reel(1, "cat", 0, 3_000, 4, moved_to_next=False),
        reel(2, "cat", 7_000, 9_000, 4, liked=True),
        reel(3, "dog", 9_000, 10_000, 5),
    ])
    out = merge_splits(reels)
    assert out.id.tolist() == [1, 3]
    first = out.iloc[0]
    assert first.merged_ids == [1, 2]
    assert first.paused_ms == 4_000
    assert first.dwell_ms == 5_000
    assert first.moved_to_next and first.liked


def test_long_absence_or_other_reel_is_not_merged():
    reels = pd.DataFrame([
        reel(1, "cat", 0, 3_000, 4, moved_to_next=False),
        reel(2, "cat", 60_000, 62_000, 4),
        reel(3, "dog", 62_000, 63_000, 5, moved_to_next=False),
        reel(4, "cow", 64_000, 65_000, 6),
    ])
    assert merge_splits(reels).id.tolist() == [1, 2, 3, 4]


def test_revisit_needs_same_feed_position_and_creator():
    reels = pd.DataFrame([
        reel(1, "cat", 0, 1_000, 1),
        reel(2, "cat", 1_000, 2_000, 2),   # same creator, new position: not a revisit
        reel(3, "dog", 2_000, 3_000, 3),
        reel(4, "cat", 3_000, 4_000, 2),   # scrolled back to position 2
    ])
    assert fix_revisits(reels).revisit.tolist() == [False, False, False, True]


def test_clean_runs_both_steps():
    reels = pd.DataFrame([
        reel(1, "cat", 0, 1_000, 1, moved_to_next=False),
        reel(2, "cat", 2_000, 3_000, 1, revisit=True),
    ])
    out = clean(reels)
    assert len(out) == 1 and not out.iloc[0].revisit
