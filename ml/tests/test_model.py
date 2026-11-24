import numpy as np
import pandas as pd

from autodoom.experiment import auc_change, fold_scores, with_embedding
from autodoom.metrics import group_auc
from autodoom.model import skip_model

DAY_MS = 86_400_000


def reels(days=5, per_day=40, seed=0):
    """Reels over several days whose label follows the first embedding dimension."""
    rng = np.random.default_rng(seed)
    n = days * per_day
    vectors = rng.normal(size=(n, 12))
    data = pd.DataFrame({
        "started_at_ms": np.repeat(np.arange(days), per_day) * DAY_MS + np.tile(np.arange(per_day), days) * 1000,
        "label": np.where(vectors[:, 0] + 0.3 * rng.normal(size=n) > 0, "SKIP", "WATCH"),
        "noise": rng.normal(size=n),
        "session": np.repeat(np.arange(days), per_day),
    })
    data.loc[::7, "noise"] = np.nan
    return with_embedding(data, "frame", vectors)


def test_embedding_enters_as_principal_components():
    data, columns = reels()
    model = skip_model(embedding=columns, components=3).fit(data[["noise", *columns]], data["label"] == "SKIP")
    assert model[-1].coef_.shape == (1, 3 + 1)


def test_head_finds_the_signal_and_beats_the_model_without_it():
    data, columns = reels()
    with_head = fold_scores(data, ["noise"], True, embedding=columns, components=12)
    without = fold_scores(data, ["noise"], True)
    assert len(with_head) == 3 * 40  # days three to five are test days
    assert group_auc(with_head["y"], with_head["p"], with_head["fold"]) > 0.9
    change = auc_change(with_head, without, n=200)
    assert change["auc_change"] > 0.3
    assert change["auc_change_ci95"][0] > 0


def test_a_model_does_not_differ_from_itself():
    data, _ = reels()
    scores = fold_scores(data, ["noise"], True)
    change = auc_change(scores, scores, n=50)
    assert change["auc_change"] == 0
    assert change["auc_change_ci95"] == (0, 0)


def test_folded_weights_reproduce_the_fitted_model():
    from autodoom.deploy import fold, predict

    data, columns = reels()
    model = skip_model(embedding=columns, components=3).fit(data[["noise", *columns]], data["label"] == "SKIP")
    weights = fold(model, ["noise"])
    assert len(weights["weights"]) == len(weights["impute"]) == 1
    assert len(weights["frame_weights"]) == len(columns)
    folded = predict(weights, data[["noise"]].to_numpy(), data[columns].to_numpy())
    assert np.isnan(data["noise"]).any()
    np.testing.assert_allclose(folded, model.predict_proba(data[["noise", *columns]])[:, 1], atol=1e-12)


def scores_with(p_and_y, days=3):
    """Out-of-fold scores with the same reels on each of ``days`` test days."""
    rows = [{"fold": fold, "p": p, "y": y} for fold in range(1, days + 1) for p, y in p_and_y]
    return pd.DataFrame(rows)


def test_auto_threshold_is_the_lowest_one_that_is_right_often_enough():
    from autodoom.deploy import auto_threshold

    # Above 0.7: nine skips in ten. Between 0.5 and 0.7: half.
    reels = [(0.75, True)] * 9 + [(0.75, False)] + [(0.55, True)] * 5 + [(0.55, False)] * 5 + [(0.2, False)] * 10
    auto = auto_threshold(scores_with(reels), days=3, precision=0.9, min_skips=30)
    assert auto["threshold"] == 0.6  # the grid's first step past the mixed reels at 0.55
    assert auto["precision"] == 0.9 and auto["skips"] == 30
    assert auto["coverage"] == 27 / 42


def test_auto_threshold_stays_locked_without_enough_days_reels_or_precision():
    from autodoom.deploy import auto_threshold

    good = [(0.75, True)] * 9 + [(0.75, False)] + [(0.2, False)] * 10
    assert auto_threshold(scores_with(good, days=2), days=3, min_skips=10) is None  # too few days
    assert auto_threshold(scores_with(good), days=3, min_skips=40) is None  # too few reels above
    poor = [(0.95, True)] * 8 + [(0.95, False)] * 2 + [(0.2, False)] * 10
    assert auto_threshold(scores_with(poor), days=3, min_skips=10) is None  # 80% right isn't enough


def test_auto_threshold_ignores_how_the_model_did_long_ago():
    from autodoom.deploy import auto_threshold

    early = pd.DataFrame([{"fold": 1, "p": 0.9, "y": False}] * 50)
    late = scores_with([(0.75, True)] * 10 + [(0.2, False)] * 5, days=3).assign(fold=lambda d: d["fold"] + 1)
    auto = auto_threshold(pd.concat([early, late]), days=3, min_skips=30)
    assert auto["threshold"] == 0.5 and auto["precision"] == 1.0
