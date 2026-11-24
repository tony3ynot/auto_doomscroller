"""Writes the fixtures the app's unit tests share with these tests.

    python ml/tests/make_fixtures.py

``decision_features.json``: made-up reels in time order and the features ``decision_features``
gives each. ``skip_model.json``: a small weight file, inputs and the P(skip) it gives.
``training.json``: labeled examples over several days, what the fitted model then gives for
some new reels, and the Auto mode threshold. The Kotlin tests (ViewingHistoryTest,
SkipModelTest, SkipTrainerTest) read the same files, so the two sides can't drift apart
unnoticed. Re-run after changing the features, the model or the weight format on purpose.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from autodoom.deploy import FORMAT, auto_threshold, predict
from autodoom.experiment import fold_scores, with_embedding
from autodoom.features import YOUTUBE, decision_features
from autodoom.model import skip_model

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


def training_fixture(days: int = 8, per_day: int = 40, dim: int = 10, components: int = 3) -> dict:
    """Examples whose label follows two features and the frame's strongest direction."""
    rng = np.random.default_rng(2)
    names = ["a", "b", "c"]

    def draw(n: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        x = rng.normal(size=(n, 3))
        factors = rng.normal(size=(n, 3)) * [3.0, 2.0, 1.2]
        frame = factors @ mixing + 0.2 * rng.normal(size=(n, dim)) + offset
        frame /= np.linalg.norm(frame, axis=1, keepdims=True)
        logit = 1.2 * x[:, 0] - 0.8 * x[:, 1] + 0.9 * factors[:, 0] - 0.4
        y = logit + rng.logistic(size=n) > 0
        x[rng.random(size=x.shape) < 0.08] = np.nan
        return x.round(4), frame.round(6), y

    mixing, offset = rng.normal(size=(3, dim)), rng.normal(size=dim)
    n = days * per_day
    x, frame, y = draw(n)
    day = np.repeat(np.arange(days), per_day)
    data = pd.DataFrame(x, columns=names)
    # Noon in Seoul on consecutive days, so the folds are the days.
    data["started_at_ms"] = START_MS + 13 * 3_600_000 + day * 86_400_000 + np.tile(np.arange(per_day), days) * 1000
    data["label"] = np.where(y, "SKIP", "WATCH")
    data["session"] = day
    data, columns = with_embedding(data, "frame", frame)

    model = skip_model(1.0, columns, components).fit(data[[*names, *columns]], y)
    new_x, new_frame, _ = draw(12)
    probe = pd.DataFrame(np.hstack([new_x, new_frame]), columns=[*names, *columns])
    scores = fold_scores(data, names, True, 1.0, columns, components)
    auto = auto_threshold(scores, days=4, precision=0.8, min_skips=20)
    none = lambda row: [None if np.isnan(v) else float(v) for v in row]
    return {
        "features": names, "components": components, "c": 1.0,
        "examples": [{"x": none(xi), "frame": fi.tolist(), "skipped": bool(yi), "day": int(di)}
                     for xi, fi, yi, di in zip(x, frame, y, day)],
        "probes": [{"x": none(xi), "frame": fi.tolist(), "p_skip": float(p)}
                   for xi, fi, p in zip(new_x, new_frame, model.predict_proba(probe)[:, 1])],
        "rolling": {"test_days": int(scores["fold"].nunique()), "p_skip_last_day": scores[scores["fold"] == scores["fold"].max()]["p"].round(6).tolist()},
        "auto": {"days": 4, "precision": 0.8, "min_skips": 20, "expected": auto},
    }


FILES = {"decision_features.json": decision_fixture, "skip_model.json": model_fixture, "training.json": training_fixture}

if __name__ == "__main__":
    FIXTURES.mkdir(exist_ok=True)
    for name, make in FILES.items():
        (FIXTURES / name).write_text(json.dumps(make(), indent=1) + "\n")
        print(FIXTURES / name)
