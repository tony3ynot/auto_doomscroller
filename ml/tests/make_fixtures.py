"""Writes the fixtures the app's unit tests share with these tests.

    python ml/tests/make_fixtures.py

``decision_features.json``: made-up reels in time order and the features ``decision_features``
gives each. ``skip_model.json``: a small weight file, inputs and the P(skip) it gives. The
Kotlin tests (ViewingHistoryTest, SkipModelTest) read the same files, so the two sides can't
drift apart unnoticed. Re-run after changing the features or the weight format on purpose.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from autodoom.deploy import FORMAT, predict
from autodoom.features import YOUTUBE, decision_features

FIXTURES = Path(__file__).parent / "fixtures"
INSTAGRAM = "com.instagram.android"
START_MS = 1_793_800_000_000  # 2026-11-04 23:06 in Seoul, so the reels run past midnight


def reels(n: int = 80, seed: int = 0) -> list[dict]:
    rng = np.random.default_rng(seed)
    creators = [f"creator{i}" for i in range(8)]
    # Lengths are counted in code points: the emoji is one character, not two.
    captions = [None, "", "short", "a longer caption with #tags and more words", "웃긴 영상 \U0001F600"]
    now, out = START_MS, []
    for i in range(n):
        now += int(rng.choice([300, 800, 2_000, 20_000])) if i % 23 else 40 * 60_000  # a long gap now and then
        dwell = int(rng.choice([400, 1_200, 3_500, 9_000, 45_000]))
        is_ad = bool(rng.random() < 0.12)
        label = None if is_ad or 2_000 <= dwell < 5_000 else ("SKIP" if dwell < 2_000 else "WATCH")
        out.append({
            "id": i + 1,
            "package": YOUTUBE if rng.random() < 0.3 else INSTAGRAM,
            "creator": str(rng.choice(creators)),
            "caption": captions[int(rng.integers(len(captions)))],
            "audio": None if rng.random() < 0.4 else "Original audio",
            "media_type": str(rng.choice(["VIDEO", "VIDEO", "VIDEO", "IMAGE", "CAROUSEL"])),
            "is_ad": is_ad,
            "paid_promotion": bool(rng.random() < 0.1),
            "like_count": None if rng.random() < 0.3 else int(rng.integers(0, 2_000_000)),
            "comment_count": None if rng.random() < 0.3 else int(rng.integers(0, 50_000)),
            "started_at_ms": now,
            "ended_at_ms": now + dwell + (5_000 if i % 11 == 0 else 0),  # some time away mid-reel
            "dwell_ms": dwell,
            "label": label,
        })
        now = out[-1]["ended_at_ms"]
    return out


def decision_fixture() -> dict:
    rows = reels()
    features = decision_features(pd.DataFrame(rows).astype({"label": object}))
    expected = [{k: (None if np.isnan(v) else float(v)) for k, v in row.items()} for row in features.to_dict("records")]
    return {"timezone": "Asia/Seoul", "features": list(features.columns), "reels": rows, "expected": expected}


def model_fixture() -> dict:
    rng = np.random.default_rng(1)
    weights = {"format": FORMAT, "features": ["a", "b", "c"], "impute": [0.5, -1.0, 2.0],
               "weights": rng.normal(size=3).round(4).tolist(), "frame_weights": rng.normal(size=6).round(4).tolist(),
               "bias": -0.3, "auto_threshold": 0.7}
    x = rng.normal(size=(5, 3)).round(4)
    x[1, 0] = x[3, 2] = x[4, 1] = np.nan
    frame = rng.normal(size=(5, 6))
    frame = (frame / np.linalg.norm(frame, axis=1, keepdims=True)).round(6)
    return {"model": weights,
            "cases": [{"x": [None if np.isnan(v) else float(v) for v in xi], "frame": fi.tolist(), "p_skip": float(p)}
                      for xi, fi, p in zip(x, frame, predict(weights, x, frame))]}


FILES = {"decision_features.json": decision_fixture, "skip_model.json": model_fixture}

if __name__ == "__main__":
    FIXTURES.mkdir(exist_ok=True)
    for name, make in FILES.items():
        (FIXTURES / name).write_text(json.dumps(make(), indent=1) + "\n")
        print(FIXTURES / name)
