"""Reading what the phone reports back over adb.

The instrumented tests under ``android/benchmark`` send each measurement as one line of JSON
in the instrumentation status, which ``adb shell am instrument -r`` prints as
``INSTRUMENTATION_STATUS: <key>=<json>`` among the test runner's own lines.

Battery cost comes from ``adb shell dumpsys batterystats``: the fuel gauge's readings in the
battery history, and the system's per-app summary.
"""

from __future__ import annotations

import json
import re
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


_HISTORY_LINE = re.compile(r"^\s+(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) (\d{3}) ?(.*)$")
_HISTORY_VALUE = re.compile(r"(?<![\w:])(status|plug|temp|volt|current|charge|skin_temp)=(\S+)")
_DURATION_PART = re.compile(r"(\d+)(ms|[dhms])")
_UNIT_SECONDS = {"d": 86_400, "h": 3_600, "m": 60, "s": 1, "ms": 0.001}


def duration_seconds(text: str) -> float:
    """``1h 2m 3s 4ms`` as seconds."""
    return sum(int(n) * _UNIT_SECONDS[unit] for n, unit in _DURATION_PART.findall(text))


def battery_history(dump: str) -> list[dict]:
    """The battery history as one row per change: ``t`` (seconds from the first entry),
    ``level`` (%), and the latest ``status``, ``plug``, ``temp`` (°C), ``volt`` (mV), ``current``
    (mA), ``charge`` (mAh) and ``skin_temp`` (°C), each carried forward until it changes."""
    rows, state, start, previous = [], {}, None, None
    section = dump.split("Battery History", 1)[-1].split("\nPer-PID Stats", 1)[0]
    for line in section.splitlines():
        match = _HISTORY_LINE.match(line)
        if not match:
            continue
        month, day, hour, minute, second, ms, level, rest = match.groups()
        # No year in the log: count days from the first entry, so a run may cross midnight
        # but not a month's end.
        clock = ((int(day) * 24 + int(hour)) * 60 + int(minute)) * 60 + int(second) + int(ms) / 1000
        start = clock if start is None else start
        changed = {key: value for key, value in _HISTORY_VALUE.findall(rest)}
        if not changed and previous == level:
            continue
        previous = level
        for key, value in changed.items():
            state[key] = value if key in ("status", "plug") else float(value) / (10 if key == "temp" else 1)
        rows.append({"t": clock - start, "level": int(level), **state})
    return rows


def on_battery(history: list[dict]) -> dict:
    """What the phone drew while unplugged, and how warm it got.

    The charge used comes from the fuel gauge's counter (``charge``, whole mAh); mean power is
    that over the time, at the time-weighted mean voltage. ``sampled_current_ma`` is the mean
    of the current readings instead (negative while discharging), as a cross-check: the two
    agree when readings are frequent, which they are while the screen is on.
    """
    rows = [(row, following["t"] - row["t"]) for row, following in zip(history, history[1:])
            if row.get("plug") == "none"]
    hours = sum(dt for _, dt in rows) / 3600
    if not hours:
        raise ValueError("the battery history has no time unplugged")

    def mean(key: str) -> float:
        held = [(row[key], dt) for row, dt in rows if key in row]
        return sum(value * dt for value, dt in held) / sum(dt for _, dt in held)

    def course(key: str) -> dict:
        values = [row[key] for row, _ in rows if key in row]
        return {"start": values[0], "end": values[-1], "max": max(values)}

    counter = [row["charge"] for row, _ in rows if "charge" in row]
    discharge = counter[0] - counter[-1]
    return {
        "minutes": hours * 60,
        "discharge_mah": discharge,
        "mean_current_ma": discharge / hours,
        "mean_power_mw": discharge / hours * mean("volt") / 1000,
        "sampled_current_ma": -mean("current"),
        "level_drop": rows[0][0]["level"] - rows[-1][0]["level"],
        "battery_temp_c": course("temp"),
        "skin_temp_c": course("skin_temp"),
    }


def battery_statistics(dump: str, uids: dict[str, str]) -> dict:
    """The "since last charge" summary: time on battery, discharge, and for each ``uids`` entry
    (name -> uid as printed, e.g. ``u0a355``) its estimated power use in mAh by component and
    its CPU time in seconds."""
    stats = dump.split("Statistics since last charge:", 1)[-1]
    out: dict = {}
    if match := re.search(r"Time on battery: (.+?) \(", stats):
        out["minutes_on_battery"] = duration_seconds(match.group(1)) / 60
    if match := re.search(r"Time on battery screen off: (.+?) \(", stats):
        out["minutes_screen_off"] = duration_seconds(match.group(1)) / 60
    for name, label in (("discharge_mah", "Discharge"), ("capacity_mah", "Estimated battery capacity")):
        if match := re.search(rf"^\s+{label}: (\d+) mAh", stats, re.M):
            out[name] = int(match.group(1))
    out["apps"] = {}
    for name, uid in uids.items():
        app: dict = {}
        if match := re.search(rf"^\s+UID {uid}: ([\d.]+).*\n\s+(.*)$", stats, re.M):
            app["estimated_mah"] = float(match.group(1))
            app["estimated_mah_by"] = {k: float(v) for k, v in re.findall(r"(?<![\w:])([a-z_]+)=([\d.]+)", match.group(2))}
        if match := re.search(rf"^  {uid}:\n(?:(?!^  \S).*\n)*?\s+Total cpu time: u=(.+?) s=(.+?)\s*$", stats, re.M):
            app["cpu_seconds"] = duration_seconds(match.group(1)) + duration_seconds(match.group(2))
        out["apps"][name] = app
    return out
