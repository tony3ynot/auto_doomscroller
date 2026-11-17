"""How long each frame encoder build takes on a phone.

    scripts/benchmark-encoder.sh results/<date>-encoder-latency

That script builds the benchmark, runs it on the connected phone and hands what it printed
to this one, which writes metrics.json, summary.md and run.json. The models' checksums are
recorded so the timings can be matched to the builds in the quantization results.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from autodoom.phone import latency_ms, status_records
from autodoom.report import write_run

NAMES = {"fp32": "FP32", "fp16": "FP16", "int8_dynamic": "INT8 dynamic range", "int8_full": "INT8 full integer"}
STATUS_KEY = "encoder_latency"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("output", type=Path, help="what `am instrument -w -r` printed")
    parser.add_argument("--models", type=Path, required=True, help="folder with the encoder_<variant>.tflite that ran")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    records = status_records(args.output.read_text(errors="replace"), STATUS_KEY)
    devices = [{k: v for k, v in r.items() if k != "kind"} for r in records if r["kind"] == "device"]
    timed = sorted((r for r in records if r["kind"] == "latency"),
                   key=lambda r: (list(NAMES).index(r["variant"]), r["delegate"], r["threads"]))
    if not devices or not timed:
        raise SystemExit(f"No measurements in {args.output}: did the benchmark run?")
    device = devices[0]

    measurements = [{
        "encoder": NAMES[r["variant"]], "delegate": r["delegate"], "threads": r["threads"],
        "load_ms": round(r["load_ms"], 3), "first_ms": r["first_us"] / 1000,
        "back_to_back": latency_ms(r["back_to_back_us"]), "paced": latency_ms(r["paced_us"]),
        "thermal_status": r["thermal_status"],
    } for r in timed]
    failures = [{"encoder": NAMES[r["variant"]], "delegate": r["delegate"], "error": r["error"]}
                for r in records if r["kind"] == "failure"]
    models = {NAMES[v]: hashlib.sha256((args.models / f"encoder_{v}.tflite").read_bytes()).hexdigest()
              for v in dict.fromkeys(r["variant"] for r in timed)}

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(
        json.dumps({"measurements": measurements, "failures": failures}, indent=2) + "\n")
    write_run(args.out, "ml/scripts/encoder_latency.py", device=device, sha256=models)

    def spread(stats: dict) -> str:
        return f"{stats['median_ms']:.1f} ms ({stats['p95_ms']:.1f})"

    rows = ["| Encoder | Runs on | Load | First run | Back to back | Paced |", "|---|---|---|---|---|---|"]
    for m in measurements:
        runs_on = "GPU" if m["delegate"] == "gpu" else f"CPU, {m['threads']} thread{'s' if m['threads'] > 1 else ''}"
        rows.append(f"| {m['encoder']} | {runs_on} | {m['load_ms']:.0f} ms | {m['first_ms']:.1f} ms | "
                    f"{spread(m['back_to_back'])} | {spread(m['paced'])} |")
    rows += [f"| {f['encoder']} | GPU | failed: {f['error']} | – | – | – |" for f in failures]
    runs, paced = measurements[0]["back_to_back"]["n"], measurements[0]["paced"]["n"]
    lines = [
        "# Encoder latency on the phone",
        f"- Phone: {device['model']} ({device['soc']}, {device['cores']} cores), Android {device['android']},",
        f"  LiteRT {device['litert']}. CPU runs use XNNPACK",
        "- One 224 px frame in, one 576-d embedding out; the time to scale the screenshot is not included",
        "- Load: mapping the model and building the interpreter. First run: the first inference after that",
        f"- Back to back: {runs} runs after {device['warmup_runs']} warm-up runs; median, 95th percentile in brackets",
        f"- Paced: {paced} runs with {device['pace_ms'] / 1000:g} s of idle before each, as when the encoder runs once per reel",
        "",
        *rows,
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
