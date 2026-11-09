import json
from datetime import date, datetime
from zoneinfo import ZoneInfo

from autodoom.data import hires_path, load_export, thumbnail_paths


def test_thumbnail_paths_skip_the_hires_frame_and_keep_order(tmp_path):
    thumbs = tmp_path / "thumbnails"
    thumbs.mkdir()
    for name in ["7_2.jpg", "7_0.jpg", "7_hires.jpg", "7_1.jpg", "8_0.jpg"]:
        (thumbs / name).write_bytes(b"")
    assert [p.name for p in thumbnail_paths(tmp_path, 7)] == ["7_0.jpg", "7_1.jpg", "7_2.jpg"]
    assert hires_path(tmp_path, 7).name == "7_hires.jpg"
    assert hires_path(tmp_path, 8) is None


def test_until_keeps_reels_up_to_the_end_of_that_day_in_seoul(tmp_path):
    def ms(*t):
        return int(datetime(*t, tzinfo=ZoneInfo("Asia/Seoul")).timestamp() * 1000)

    # 00:30 in Seoul on the 10th is still the 9th in UTC; it must count as the 10th.
    starts = [ms(2026, 11, 9, 23, 50), ms(2026, 11, 10, 0, 30)]
    (tmp_path / "sessions.jsonl").write_text(json.dumps({"id": 1}) + "\n")
    (tmp_path / "reels.jsonl").write_text("".join(
        json.dumps({"id": i, "started_at_ms": s, "ended_at_ms": s + 1000}) + "\n" for i, s in enumerate(starts)
    ))
    _, reels = load_export(tmp_path, until=date(2026, 11, 9))
    assert reels["id"].tolist() == [0]
    _, everything = load_export(tmp_path)
    assert len(everything) == 2
