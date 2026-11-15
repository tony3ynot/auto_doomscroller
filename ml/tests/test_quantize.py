from autodoom.quantize import calibration_frames


def touch(folder, *names):
    for name in names:
        (folder / name).write_bytes(b"")


def test_calibration_uses_first_frames_only(tmp_path):
    touch(tmp_path, "1_0.jpg", "1_1.jpg", "1_hires.jpg", "2_0.jpg", "2_2.jpg")
    assert [p.name for p in calibration_frames(tmp_path, 10)] == ["1_0.jpg", "2_0.jpg"]


def test_calibration_is_spread_over_the_recording(tmp_path):
    # Ids sort as numbers, not text: 9 comes before 10.
    touch(tmp_path, *(f"{i}_0.jpg" for i in range(1, 102)))
    picked = [int(p.stem.split("_")[0]) for p in calibration_frames(tmp_path, 5)]
    assert picked == [1, 26, 51, 76, 101]
