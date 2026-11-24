"""How the predictions made on the phone held up.

    python ml/scripts/live_predictions.py data/<export> --model ml/models/skip_model.json --out results/<date>-live-predictions

The app stores P(skip) and the moment it was ready with every reel it scored. This takes the
reels of an export that have one and asks three things. Did the phone compute what the
offline code computes for the same reel, from the same weight file? How well did the
probabilities rank what the user then did, on days the model had never seen? And how long
into a reel was the answer ready? Writes metrics.json, summary.md and run.json. Aggregates only.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

import numpy as np

from autodoom.deploy import ENCODER, predict
from autodoom.embed import embeddings
from autodoom.experiment import build, with_embedding
from autodoom.labels import SKIP
from autodoom.metrics import bootstrap_ci, coverage_at_precision, group_auc, skip_auc
from autodoom.report import write_run

THRESHOLDS = (0.5, 0.6, 0.7, 0.8)


def agreement(phone: np.ndarray, offline: np.ndarray) -> dict:
    gap = np.abs(phone - offline)
    return {"reels": len(gap), "correlation": float(np.corrcoef(phone, offline)[0, 1]), "mean_gap": float(gap.mean()),
            "median_gap": float(np.median(gap)), "p95_gap": float(np.percentile(gap, 95)),
            "within_0.05": float((gap <= 0.05).mean())}


def accuracy(y: np.ndarray, p: np.ndarray, day: np.ndarray, session: np.ndarray) -> dict:
    return {
        "auc": group_auc(y, p, day),
        "auc_ci95": bootstrap_ci(group_auc, y, p, day, strata=day),
        "auc_by_day": {d: skip_auc(y[day == d], p[day == d]) for d in np.unique(day)},
        "session_auc": group_auc(y, p, session, weighted=True),
        "pooled_auc": skip_auc(y, p),
        "coverage_at_80": float(np.mean([coverage_at_precision(y[day == d], p[day == d], 0.8).coverage
                                         for d in np.unique(day)])),
        # What Auto mode would have done with one threshold fixed in advance.
        "at_threshold": {str(t): {"skipped": int((p >= t).sum()),
                                  "precision": float(y[p >= t].mean()) if (p >= t).any() else None,
                                  "coverage": float((y & (p >= t)).sum() / y.sum())} for t in THRESHOLDS},
    }


def spread(values: np.ndarray) -> dict:
    return {"median": float(np.median(values)), "p95": float(np.percentile(values, 95)), "max": float(values.max())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--model", type=Path, required=True, help="the weight file that was on the phone")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--until", type=date.fromisoformat, help="last day to use (YYYY-MM-DD)")
    parser.add_argument("--without", type=date.fromisoformat, nargs="+", default=[],
                        help="days to leave out altogether (YYYY-MM-DD), e.g. ones spent testing the app")
    args = parser.parse_args()

    weights = json.loads(args.model.read_text())
    data = build(args.export, 1, {}, args.until, args.without)
    data, frame = with_embedding(data, "frame", embeddings(args.export, data, ENCODER).to_numpy())
    scored = data[data["p_skip"].notna()].copy()
    replayable = scored[scored["has_frame"]].copy()
    replayable["offline"] = predict(weights, replayable[weights["features"]].to_numpy(dtype=float),
                                    replayable[frame].to_numpy())

    judged = replayable[replayable["label"].notna() & ~replayable["revisit"]]
    y, day = (judged["label"] == SKIP).to_numpy(), judged["day"].astype(str).to_numpy()
    session = judged["session"].to_numpy()
    after_frame = (scored["predicted_at_ms"] - scored["first_frame_ms"]).dropna().to_numpy()
    results = {
        "scored": len(scored),
        "days": [str(scored["day"].min()), str(scored["day"].max())],
        "agreement": agreement(replayable["p_skip"].to_numpy(), replayable["offline"].to_numpy()),
        "judged": {"reels": len(judged), "skip_share": float(y.mean())},
        "phone": accuracy(y, judged["p_skip"].to_numpy(), day, session),
        "offline": accuracy(y, judged["offline"].to_numpy(), day, session),
        "ready_ms": spread(scored["predicted_at_ms"].to_numpy()) | {
            "within_500": float((scored["predicted_at_ms"] <= 500).mean())},
        "after_first_frame_ms": spread(after_frame),
    }

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    trained = weights.get("trained", {})
    write_run(args.out, "ml/scripts/live_predictions.py", args.export, model_trained=trained, scored=len(scored),
              until=args.until, without=args.without)

    a, phone, offline = results["agreement"], results["phone"], results["offline"]
    rows = ["| P(skip) at least | Reels Auto mode would skip | Of those, really skipped | Share of all SKIPs |", "|---|---|---|---|"]
    for t, r in phone["at_threshold"].items():
        precision = f"{r['precision']:.0%}" if r["precision"] is not None else "–"
        rows.append(f"| {t} | {r['skipped']} | {precision} | {r['coverage']:.0%} |")
    lines = [
        "# Predictions made on the phone",
        f"- {results['scored']} reels scored on the phone, {results['days'][0]} – {results['days'][1]}, by a model fitted on",
        f"  {trained.get('reels', '?')} reels up to {trained.get('last_day', '?')}: every one of these days is new to it",
        "",
        "Same answer as offline?",
        f"- {a['reels']} of them have a stored first frame, so the offline code can score them from the same weights",
        f"- The two probabilities differ by {a['median_gap']:.3f} at the median and {a['p95_gap']:.3f} at the 95th percentile;",
        f"  {a['within_0.05']:.0%} are within 0.05, and they correlate at {a['correlation']:.3f}",
        "",
        "Right about the user?",
        f"- {results['judged']['reels']} of the scored reels got a label ({results['judged']['skip_share']:.0%} SKIP)",
        f"- Skip AUC of the phone's probabilities, mean of the days: {phone['auc']:.3f}",
        f"  ({phone['auc_ci95'][0]:.2f}–{phone['auc_ci95'][1]:.2f}); of the offline ones on the same reels: {offline['auc']:.3f}",
        f"  ({offline['auc_ci95'][0]:.2f}–{offline['auc_ci95'][1]:.2f})",
        f"- By day: {', '.join(f'{auc:.2f}' for auc in phone['auc_by_day'].values())}. Inside a viewing session: {phone['session_auc']:.3f}",
        f"- Coverage at 80% precision with the best threshold for each day: {phone['coverage_at_80']:.0%}",
        "",
        *rows,
        "",
        "Ready in time?",
        f"- The probability was ready {results['ready_ms']['median']:.0f} ms into the reel at the median and",
        f"  {results['ready_ms']['p95']:.0f} ms at the 95th percentile; {results['ready_ms']['within_500']:.0%} within half a second",
        f"- Of that, {results['after_first_frame_ms']['median']:.0f} ms (95th percentile {results['after_first_frame_ms']['p95']:.0f} ms) came after the",
        "  first frame was captured: preparing it, the encoder and the model",
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
