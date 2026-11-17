"""Reading what the on-device benchmarks report back.

The instrumented tests under ``android/benchmark`` send each measurement as one line of JSON
in the instrumentation status, which ``adb shell am instrument -r`` prints as
``INSTRUMENTATION_STATUS: <key>=<json>`` among the test runner's own lines.
"""

from __future__ import annotations

import json
from collections.abc import Sequence

import numpy as np


def status_records(output: str, key: str) -> list[dict]:
    """The JSON records sent under ``key``, in the order they were sent."""
    prefix = f"INSTRUMENTATION_STATUS: {key}="
    return [json.loads(line[len(prefix):]) for line in output.splitlines() if line.startswith(prefix)]


def latency_ms(microseconds: Sequence[float]) -> dict:
    """Median, 95th percentile and range of a series of timings, in milliseconds."""
    ms = np.asarray(microseconds, dtype=float) / 1000
    stats = {"median_ms": np.median(ms), "p95_ms": np.percentile(ms, 95), "min_ms": ms.min(), "max_ms": ms.max()}
    return {"n": len(ms)} | {name: round(float(value), 3) for name, value in stats.items()}
