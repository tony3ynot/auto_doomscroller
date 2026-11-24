"""Fit the skip model on an export and write it as the weight file the app reads.

    python -m autodoom.deploy data/<export> --out ml/models/skip_model.json [--until YYYY-MM-DD]

The model is the one the experiments settled on: context + creator history + a few principal
components of the frame embedding (``model.skip_model``). Projection, imputation, scaling
and regression are all linear, so they fold into

    logit = bias + sum(weights[i] * x[i]) + sum(frame_weights[j] * e[j])

where ``x`` are the decision-time features (a missing one replaced by ``impute[i]``) and ``e``
is the L2-normalised frame embedding. The app computes ``x`` and ``e`` itself and needs
nothing else. The file is fitted on one person's viewing history: it stays out of git.

The file also says whether Auto mode may act on the model, and from which probability
(``auto_threshold``). That is decided on the most recent days, each scored by a model that
had only seen the days before it.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from datetime import date
from pathlib import Path

import numpy as np
from sklearn.pipeline import Pipeline

FORMAT = 1
ENCODER = "mobilenet_v3_small"
AUTO_DAYS = 14
AUTO_PRECISION = 0.9
AUTO_MIN_SKIPS = 30
AUTO_THRESHOLDS = tuple(round(0.5 + 0.05 * i, 2) for i in range(10))  # 0.50 ... 0.95


def fold(model: Pipeline, features: Sequence[str]) -> dict:
    """The weights of a fitted ``skip_model`` whose inputs were ``features`` then an embedding."""
    reduce, imputer, scaler, regression = model.steps[0][1], model[-3], model[-2], model[-1]
    pca = reduce.named_transformers_["pca"]
    k = pca.n_components_
    per_input = regression.coef_[0] / scaler.scale_  # principal components first, then the features
    frame_weights = pca.components_.T @ per_input[:k]
    bias = regression.intercept_[0] - per_input @ scaler.mean_ - pca.mean_ @ frame_weights
    return {
        "format": FORMAT,
        "features": list(features),
        "impute": imputer.statistics_[k:].tolist(),
        "weights": per_input[k:].tolist(),
        "frame_weights": frame_weights.tolist(),
        "bias": float(bias),
    }


def auto_threshold(scores, days: int = AUTO_DAYS, precision: float = AUTO_PRECISION,
                   min_skips: int = AUTO_MIN_SKIPS) -> dict | None:
    """The lowest P(skip) from which Auto mode would have been right often enough, or None.

    ``scores`` are ``experiment.fold_scores``: every test day scored by a model fitted on the
    days before it. Only the last ``days`` test days count, because the probabilities of a
    model with little data behind it can't be trusted, however confident. A threshold needs at
    least ``min_skips`` reels above it, ``precision`` of them really skipped.
    """
    recent = scores[scores["fold"] > scores["fold"].max() - days]
    y, p = recent["y"].to_numpy(), recent["p"].to_numpy()
    if recent["fold"].nunique() < days or not y.any():
        return None
    for threshold in AUTO_THRESHOLDS:
        above = p >= threshold
        if above.sum() >= min_skips and y[above].mean() >= precision:
            return {"threshold": threshold, "precision": float(y[above].mean()), "skips": int(above.sum()),
                    "coverage": float(y[above].sum() / y.sum()), "days": days}
    return None


def predict(weights: dict, x: np.ndarray, frame: np.ndarray) -> np.ndarray:
    """P(skip) from a weight file, the way the app computes it. ``x`` may hold NaN."""
    x = np.where(np.isnan(x), np.asarray(weights["impute"]), x)
    logit = weights["bias"] + x @ np.asarray(weights["weights"]) + frame @ np.asarray(weights["frame_weights"])
    return 1 / (1 + np.exp(-logit))


def main() -> None:
    from .embed import embeddings
    from .experiment import CONTEXT, CREATOR, build, fold_scores, with_embedding
    from .labels import SKIP
    from .model import skip_model
    from .report import git_commit

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    parser.add_argument("--components", type=int, default=8, help="principal components of the frame embedding")
    parser.add_argument("--until", type=date.fromisoformat, help="last day to train on (YYYY-MM-DD)")
    parser.add_argument("--without", type=date.fromisoformat, nargs="+", default=[],
                        help="days to leave out altogether (YYYY-MM-DD), e.g. ones spent testing the app")
    args = parser.parse_args()

    data = build(args.export, 1, {}, args.until, args.without)
    data, columns = with_embedding(data, "frame", embeddings(args.export, data, ENCODER).to_numpy())
    train = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]
    features = CONTEXT + CREATOR
    y = (train["label"] == SKIP).to_numpy()
    model = skip_model(args.c, columns, args.components).fit(train[[*features, *columns]], y)

    weights = fold(model, features)
    folded = predict(weights, train[features].to_numpy(dtype=float), train[columns].to_numpy())
    drift = float(np.abs(folded - model.predict_proba(train[[*features, *columns]])[:, 1]).max())
    if drift > 1e-6:  # the embeddings are float32, and so is scikit-learn's projection of them
        raise SystemExit(f"folded weights disagree with the fitted model by {drift:g}")

    auto = auto_threshold(fold_scores(train, features, True, args.c, columns, args.components))
    weights["auto_threshold"] = auto["threshold"] if auto else None
    weights["trained"] = {"commit": git_commit(), "encoder": ENCODER, "reels": len(train), "skip_share": float(y.mean()),
                          "first_day": str(train["day"].min()), "last_day": str(train["day"].max()), "auto": auto}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(weights) + "\n")
    print(f"{args.out}: {len(train)} labeled first impressions, {train['day'].min()} – {train['day'].max()}, "
          f"{y.mean():.0%} skipped; P(skip) on them from {folded.min():.2f} to {folded.max():.2f}, "
          f"median {np.median(folded):.2f}")
    print(f"Auto mode: from P(skip) {auto['threshold']:.2f}, {auto['precision']:.0%} of {auto['skips']} right and "
          f"{auto['coverage']:.0%} of the skips, on the last {auto['days']} days" if auto
          else f"Auto mode: locked, no threshold is {AUTO_PRECISION:.0%} right on the last {AUTO_DAYS} days")


if __name__ == "__main__":
    main()
