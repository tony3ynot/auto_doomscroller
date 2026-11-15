"""The skip model: logistic regression on decision-time features and taste scores.

Small on purpose. With a few thousand labeled reels per user, a linear model over a handful of
summary features generalises better than one fitted on raw embeddings, and its weights fit in
a few lines of Kotlin. A frame embedding goes in as a few principal components, so on the
phone the whole model is still one weight vector.
"""

from __future__ import annotations

from collections.abc import Sequence

from sklearn.compose import make_column_transformer
from sklearn.decomposition import PCA
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline, make_pipeline
from sklearn.preprocessing import StandardScaler


def skip_model(c: float = 1.0, embedding: Sequence[str] = (), components: int = 8) -> Pipeline:
    """Median imputation, standardisation, L2 logistic regression. Predicts P(SKIP).

    Columns named in ``embedding`` (an embedding with no missing rows) are first reduced to
    their ``components`` leading principal components, fitted on the training rows like
    everything else.
    """
    steps = [SimpleImputer(strategy="median"), StandardScaler(), LogisticRegression(C=c, max_iter=1000)]
    if embedding:
        reduce = make_column_transformer((PCA(components, random_state=0), list(embedding)), remainder="passthrough")
        steps.insert(0, reduce)
    return make_pipeline(*steps)
