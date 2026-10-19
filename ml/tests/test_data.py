from autodoom.data import hires_path, thumbnail_paths


def test_thumbnail_paths_skip_the_hires_frame_and_keep_order(tmp_path):
    thumbs = tmp_path / "thumbnails"
    thumbs.mkdir()
    for name in ["7_2.jpg", "7_0.jpg", "7_hires.jpg", "7_1.jpg", "8_0.jpg"]:
        (thumbs / name).write_bytes(b"")
    assert [p.name for p in thumbnail_paths(tmp_path, 7)] == ["7_0.jpg", "7_1.jpg", "7_2.jpg"]
    assert hires_path(tmp_path, 7).name == "7_hires.jpg"
    assert hires_path(tmp_path, 8) is None
