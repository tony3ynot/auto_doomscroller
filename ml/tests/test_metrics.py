import numpy as np
import pytest

from autodoom.metrics import coverage_at_precision, skip_auc


def test_perfect_ranking():
    is_skip = np.array([0, 0, 1, 1])
    p_skip = np.array([0.1, 0.2, 0.8, 0.9])
    assert skip_auc(is_skip, p_skip) == 1.0
    point = coverage_at_precision(is_skip, p_skip, min_precision=0.9)
    assert point.coverage == 1.0
    assert point.precision == 1.0
    assert point.threshold == pytest.approx(0.8)


def test_only_the_confident_head_is_covered():
    # Top two scores are true skips, then a non-skip, then the last skip.
    is_skip = np.array([1, 1, 0, 1, 0])
    p_skip = np.array([0.95, 0.9, 0.8, 0.7, 0.1])
    point = coverage_at_precision(is_skip, p_skip, min_precision=0.9)
    assert point.coverage == pytest.approx(2 / 3)
    assert point.threshold == pytest.approx(0.9)


def test_unreachable_precision_covers_nothing():
    is_skip = np.array([0, 1, 0, 1])
    p_skip = np.array([0.9, 0.1, 0.8, 0.2])
    point = coverage_at_precision(is_skip, p_skip, min_precision=0.9)
    assert point.coverage == 0.0
    assert point.threshold is None
