"""What a session costs in battery, by how the app was set.

    scripts/measure-battery.sh <name>            # once per setting, phone off the charger
    python ml/scripts/battery.py data/battery --out results/<date>-battery

Each run is one stretch on battery with reels swiped at a fixed pace, read from the phone's
own statistics: the charge the fuel gauge counted, the voltage and temperatures it logged,
and the system's estimate of each app's share. A run is two files in the folder:
``<name>.batterystats.txt``, what ``adb shell dumpsys batterystats`` printed at its end (the
statistics were reset at its start), and ``<name>.conditions``, lines of ``key=value`` saying
how the app was set. Writes metrics.json, summary.md and run.json: totals, this app and the
feed apps only.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from autodoom.phone import battery_history, battery_statistics, on_battery
from autodoom.report import write_run

FEEDS = ("instagram", "youtube")


def read_conditions(path: Path) -> dict:
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def setting(conditions: dict) -> str:
    """How the app was set, in a few words. A setting changed mid-run is called out."""
    mode = conditions.get("start_mode", "?").capitalize()
    parts = [mode] if mode == "Off" else [mode, *(["hands-free"] if conditions.get("start_hands_free") == "true" else [])]
    changed = [key for key in ("mode", "hands_free", "brightness", "research_mode")
               if conditions.get(f"stop_{key}") not in (None, conditions.get(f"start_{key}"))]
    return " + ".join(parts) + (f" ({', '.join(changed)} changed during the run)" if changed else "")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", type=Path, help="folder written by scripts/measure-battery.sh")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", nargs="+", help="names of the runs to report, in this order (default: all, by start time)")
    args = parser.parse_args()

    found = {p.name.removesuffix(".conditions"): read_conditions(p) for p in args.runs.glob("*.conditions")}
    names = args.only or sorted(found, key=lambda n: found[n].get("start_time", ""))
    runs = {}
    for name in names:
        conditions = found[name]
        dump = (args.runs / f"{name}.batterystats.txt").read_text(errors="replace").replace("\r", "")
        uids = {key.removeprefix("uid_"): uid for key, uid in conditions.items() if key.startswith("uid_") and uid}
        used, stats = on_battery(battery_history(dump)), battery_statistics(dump, uids)
        hours = used["minutes"] / 60
        runs[name] = {
            "setting": setting(conditions),
            "brightness": conditions.get("start_brightness"),
            "brightness_auto": conditions.get("start_brightness_auto") == "1",
            **used,
            "percent_per_hour": used["mean_current_ma"] / stats["capacity_mah"] * 100,
            "system_discharge_mah": stats.get("discharge_mah"),
            "minutes_screen_off": stats.get("minutes_screen_off"),
            "apps": {app: {**values, "cpu_seconds_per_minute": values["cpu_seconds"] / used["minutes"],
                           "estimated_ma": values.get("estimated_mah", 0.0) / hours} if "cpu_seconds" in values else values
                     for app, values in stats["apps"].items()},
        }

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(runs, indent=2) + "\n")
    first = found[names[0]]
    write_run(args.out, "ml/scripts/battery.py", device={"model": first.get("device"), "android": first.get("android")},
              runs={name: found[name].get("start_time") for name in names})

    rows = ["| Run | Setting | Minutes | Discharge | Mean power | Battery per hour | App CPU | App, estimated | Feed apps, estimated | Skin temperature |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for name, r in runs.items():
        app = r["apps"].get("app", {})
        feeds = sum(r["apps"].get(feed, {}).get("estimated_ma", 0.0) for feed in FEEDS)
        skin = r["skin_temp_c"]
        rows.append("| " + " | ".join([
            name, r["setting"], f"{r['minutes']:.0f}", f"{r['discharge_mah']:.0f} mAh", f"{r['mean_power_mw']:.0f} mW",
            f"{r['percent_per_hour']:.1f}%",
            f"{app['cpu_seconds_per_minute']:.1f} s/min" if "cpu_seconds" in app else "–",
            f"{app['estimated_ma']:.1f} mA" if "cpu_seconds" in app else "–",
            f"{feeds:.0f} mA", f"{skin['start']:.1f} → {skin['end']:.1f} °C",
        ]) + " |")
    lines = [
        "# Battery cost of a session",
        f"- Phone: {first.get('device')}, Android {first.get('android')}. One run is one stretch unplugged with a reel swiped",
        f"  past every {first.get('swipe_every_s', '?')} s, read from the phone's battery statistics (`dumpsys batterystats`) after a reset",
        "- Discharge: what the fuel gauge counted, in whole mAh. Mean power: that over the time, at the",
        "  mean voltage. Battery per hour: the same as a share of the battery's capacity",
        "- App CPU: processor time this app used per minute of the run. App, estimated: the system's",
        "  estimate of this app's draw, which counts its processor and camera time but not the work the",
        "  system does on its behalf, such as taking a screenshot. Feed apps: the same for Instagram and YouTube",
        "- Skin temperature: at the start and at the end of the run",
        "",
        *rows,
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
