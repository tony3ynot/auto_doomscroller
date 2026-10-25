import numpy as np
import pytest

from autodoom.metrics import bootstrap_ci, coverage_at_precision, group_auc, skip_auc


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



def test_bootstrap_interval_brackets_the_estimate():
    rng = np.random.default_rng(1)
    is_skip = rng.random(300) < 0.4
    p_skip = is_skip * 0.3 + rng.random(300)
    low, high = bootstrap_ci(skip_auc, is_skip, p_skip, n=300)
    assert low < skip_auc(is_skip, p_skip) < high
    assert high - low < 0.2


def test_group_auc_ignores_offsets_between_groups():
    # Each group is ranked perfectly, but group 1 scores higher overall with fewer skips.
    is_skip = np.array([0, 1, 0, 0, 0, 1])
    p_skip = np.array([0.1, 0.2, 0.7, 0.8, 0.85, 0.9])
    groups = np.array([0, 0, 1, 1, 1, 1])
    assert skip_auc(is_skip, p_skip) < 1.0
    assert group_auc(is_skip, p_skip, groups) == 1.0
    low, high = bootstrap_ci(group_auc, is_skip, p_skip, groups, strata=groups, n=50)
    assert low == high == 1.0
