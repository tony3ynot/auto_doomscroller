"""The skip model: logistic regression on decision-time features and taste scores.

Small on purpose. With a few hundred labeled reels per user, a linear model over a handful of
summary features generalises better than one fitted on raw embeddings, and its weights fit in
a few lines of Kotlin.
"""

from __future__ import annotations

from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline, make_pipeline
from sklearn.preprocessing import StandardScaler


def skip_model(c: float = 1.0) -> Pipeline:
    """Median imputation, standardisation, L2 logistic regression. Predicts P(SKIP)."""
    return make_pipeline(
        SimpleImputer(strategy="median"),
        StandardScaler(),
        LogisticRegression(C=c, max_iter=1000),
    )
